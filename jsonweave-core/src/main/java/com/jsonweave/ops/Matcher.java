package com.jsonweave.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsonweave.SpecException;
import com.jsonweave.TransformException;
import com.jsonweave.path.JsonPath;
import com.jsonweave.spec.Context;
import com.jsonweave.spec.Expr;
import com.jsonweave.spec.SpecCompiler;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Mongo-style match objects, compiled to predicate trees. Used by {@code #filter},
 * {@code #anyMatch}/{@code #allMatch}/{@code #noneMatch} and {@code #if}.
 *
 * <pre>
 *   {"@.status": "SHIPPED"}                     equality
 *   {"@.total": {"#gt": 100, "#lte": 500}}      comparison operators
 *   {"@.tier": {"#in": ["GOLD", "SILVER"]}}     membership
 *   {"#or": [{...}, {...}]}                     combinators: #and, #or, #not
 *   {"@.customer.tier": "GOLD"}                 dotted paths reach into the element
 *   {"@": {"#gt": 10}}                          the element itself
 *   {"@.status": "$.wantedStatus"}              right-hand sides are expressions too
 *   {"#mvel": "it.total > 100"}                 expression predicate (must return boolean)
 * </pre>
 *
 * Missing-field semantics follow Mongo: {@code #eq}/{@code #gt}/… never match a missing
 * field, {@code #ne} and {@code #nin} do, {@code #exists} tests presence explicitly.
 */
@FunctionalInterface
public interface Matcher {

    boolean test(Context ctx);

    static Matcher compile(JsonNode spec, String specPath, SpecCompiler compiler) {
        if (!spec.isObject()) {
            throw new SpecException("a match object is required, got " + spec.getNodeType(), specPath);
        }
        // {"#mvel": "..."} / {"#js": "..."} / {"#expr": "name"}: an expression predicate
        if (spec.size() == 1) {
            String only = spec.fieldNames().next();
            if (compiler.isExpressionOp(only)) {
                Expr predicate = compiler.compileExpr(spec, specPath);
                return ctx -> {
                    JsonNode r = predicate.eval(ctx);
                    if (r == null || !r.isBoolean()) {
                        throw new TransformException(only + " predicate must return a boolean, got "
                                + (r == null ? "missing" : r.getNodeType()), specPath);
                    }
                    return r.booleanValue();
                };
            }
        }
        List<Matcher> all = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = spec.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            JsonNode value = e.getValue();
            String path = specPath + "." + key;
            switch (key) {
                case "#and" -> all.add(allOf(compileList(value, path, compiler)));
                case "#or" -> all.add(anyOf(compileList(value, path, compiler)));
                case "#not" -> {
                    Matcher inner = compile(value, path, compiler);
                    all.add(ctx -> !inner.test(ctx));
                }
                default -> {
                    if (key.startsWith("#")) {
                        throw new SpecException("unknown match combinator '" + key
                                + "' (expected #and, #or, #not, an expression predicate, or a field path)",
                                specPath);
                    }
                    all.add(fieldMatcher(key, value, path, compiler));
                }
            }
        }
        return allOf(all);
    }

    private static List<Matcher> compileList(JsonNode value, String path, SpecCompiler compiler) {
        if (!value.isArray()) {
            throw new SpecException("#and/#or expect an array of match objects", path);
        }
        List<Matcher> matchers = new ArrayList<>();
        for (int i = 0; i < value.size(); i++) {
            matchers.add(compile(value.get(i), path + "[" + i + "]", compiler));
        }
        return matchers;
    }

    private static Matcher allOf(List<Matcher> matchers) {
        if (matchers.size() == 1) {
            return matchers.get(0);
        }
        Matcher[] arr = matchers.toArray(new Matcher[0]);
        return ctx -> {
            for (Matcher m : arr) {
                if (!m.test(ctx)) {
                    return false;
                }
            }
            return true;
        };
    }

    private static Matcher anyOf(List<Matcher> matchers) {
        Matcher[] arr = matchers.toArray(new Matcher[0]);
        return ctx -> {
            for (Matcher m : arr) {
                if (m.test(ctx)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static Matcher fieldMatcher(String key, JsonNode value, String path, SpecCompiler compiler) {
        JsonPath field;
        try {
            field = JsonPath.parseKeyPath(key);
        } catch (IllegalArgumentException bad) {
            throw new SpecException(bad.getMessage(), path);
        }
        boolean isOpObject = value.isObject() && value.size() > 0;
        if (isOpObject) {
            for (Iterator<String> it = value.fieldNames(); it.hasNext(); ) {
                if (!it.next().startsWith("#")) {
                    isOpObject = false;
                    break;
                }
            }
        }
        if (!isOpObject) {
            Expr rhs = compiler.compileExpr(value, path);
            return ctx -> {
                JsonNode actual = field.resolve(ctx);
                return actual != null && JsonCompare.equalsNode(actual, rhs.eval(ctx));
            };
        }
        List<Matcher> ops = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> op = it.next();
            ops.add(opMatcher(field, op.getKey(), op.getValue(), path + "." + op.getKey(), compiler));
        }
        return allOf(ops);
    }

    private static Matcher opMatcher(JsonPath field, String op, JsonNode arg, String path,
                                     SpecCompiler compiler) {
        switch (op) {
            case "#eq": {
                Expr rhs = compiler.compileExpr(arg, path);
                return ctx -> {
                    JsonNode actual = field.resolve(ctx);
                    return actual != null && JsonCompare.equalsNode(actual, rhs.eval(ctx));
                };
            }
            case "#ne": {
                Expr rhs = compiler.compileExpr(arg, path);
                return ctx -> {
                    JsonNode actual = field.resolve(ctx);
                    return actual == null || !JsonCompare.equalsNode(actual, rhs.eval(ctx));
                };
            }
            case "#gt":
                return comparison(field, compiler.compileExpr(arg, path), c -> c > 0);
            case "#gte":
                return comparison(field, compiler.compileExpr(arg, path), c -> c >= 0);
            case "#lt":
                return comparison(field, compiler.compileExpr(arg, path), c -> c < 0);
            case "#lte":
                return comparison(field, compiler.compileExpr(arg, path), c -> c <= 0);
            case "#in":
            case "#nin": {
                if (!arg.isArray()) {
                    throw new SpecException(op + " expects an array", path);
                }
                List<Expr> options = new ArrayList<>();
                for (int i = 0; i < arg.size(); i++) {
                    options.add(compiler.compileExpr(arg.get(i), path + "[" + i + "]"));
                }
                Expr[] opts = options.toArray(new Expr[0]);
                boolean negate = op.equals("#nin");
                return ctx -> {
                    JsonNode actual = field.resolve(ctx);
                    if (actual == null) {
                        return negate;
                    }
                    for (Expr option : opts) {
                        if (JsonCompare.equalsNode(actual, option.eval(ctx))) {
                            return !negate;
                        }
                    }
                    return negate;
                };
            }
            case "#exists": {
                if (!arg.isBoolean()) {
                    throw new SpecException("#exists expects true or false", path);
                }
                boolean wanted = arg.booleanValue();
                return ctx -> (field.resolve(ctx) != null) == wanted;
            }
            default:
                throw new SpecException("unknown match operator '" + op
                        + "' (expected #eq, #ne, #gt, #gte, #lt, #lte, #in, #nin, #exists)", path);
        }
    }

    private static Matcher comparison(JsonPath field, Expr rhs, java.util.function.IntPredicate accept) {
        return ctx -> {
            JsonNode actual = field.resolve(ctx);
            if (actual == null) {
                return false;
            }
            JsonNode expected = rhs.eval(ctx);
            return expected != null && accept.test(JsonCompare.compare(actual, expected));
        };
    }
}
