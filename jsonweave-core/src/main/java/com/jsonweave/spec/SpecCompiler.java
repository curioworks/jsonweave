package com.jsonweave.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jsonweave.ExpressionEngine;
import com.jsonweave.SpecException;
import com.jsonweave.TransformException;
import com.jsonweave.WeaveFunction;
import com.jsonweave.ops.Matcher;
import com.jsonweave.path.JsonPath;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles a spec document into an {@link Expr} tree. All validation happens here —
 * unknown operations, undeclared variables and malformed arguments fail with a
 * {@link SpecException} pointing at the offending spec path. The compiled tree does
 * zero string parsing or spec interpretation at apply time.
 */
public final class SpecCompiler {

    /** Satellite keys that accompany a primary operation rather than being one. */
    private static final Set<String> SATELLITES = Set.of("#default", "#on", "#then", "#else");

    private static final Set<String> VALUE_OPS = Set.of(
            "#get", "#literal", "#if", "#lookup", "#concat", "#upper", "#lower", "#trim",
            "#replace", "#substring", "#toString", "#toNumber", "#entries");

    /**
     * Stream terminals that also work standalone, outside a pipeline: the argument then
     * supplies the data, e.g. {@code {"#count": "$.shipments"}}. Aggregates operate on
     * the elements themselves in this form.
     */
    private static final Set<String> STANDALONE_STREAM_OPS = Set.of(
            "#count", "#first", "#last", "#distinct", "#reverse", "#sum", "#min", "#max", "#avg");

    /** Every built-in {@code #} keyword, for collision checks and typo suggestions. */
    public static final Set<String> ALL_BUILTIN_OPS;

    static {
        Set<String> ops = new HashSet<>();
        ops.addAll(VALUE_OPS);
        ops.addAll(SATELLITES);
        ops.addAll(Pipeline.STAGES);
        ops.addAll(Pipeline.TERMINALS);
        ops.addAll(Set.of("#stream", "#spread", "#let", "#chain",
                "#and", "#or", "#not", "#eq", "#ne", "#gt", "#gte", "#lt", "#lte",
                "#in", "#nin", "#exists", "#path", "#order", "#of", "#start", "#end",
                "#find", "#with", "#key", "#value", "#expr", "#expressions"));
        ALL_BUILTIN_OPS = Set.copyOf(ops);
    }

    private final Map<String, WeaveFunction> functions;
    private final Map<String, ExpressionEngine> engines;      // registration order = dialect preference
    private final Map<String, JsonNode> externalCatalog;
    private final Map<String, JsonNode> localCatalog = new LinkedHashMap<>(); // from #expressions blocks
    private final Set<String> knownVars = new HashSet<>();

    public SpecCompiler(Map<String, WeaveFunction> functions, Map<String, ExpressionEngine> engines,
                        Map<String, JsonNode> externalCatalog) {
        this.functions = functions;
        this.engines = engines;
        this.externalCatalog = externalCatalog;
    }

    Map<String, WeaveFunction> functions() {
        return functions;
    }

    /** Whether {@code key} is an expression op usable as a match-object predicate ({@code #expr} or a registered engine). */
    public boolean isExpressionOp(String key) {
        return key.equals("#expr") || (key.startsWith("#") && engines.containsKey(key.substring(1)));
    }

    // ------------------------------------------------------------------ roots

    /** Compiles a whole spec: a chain (top-level array or {@code #chain}) or a single step. */
    public Expr compileRoot(JsonNode spec) {
        if (spec == null || spec.isMissingNode()) {
            throw new SpecException("spec is empty", "$");
        }
        if (spec.isArray()) {
            return compileChain((ArrayNode) spec, "$");
        }
        if (spec.isObject() && spec.has("#chain")) {
            if (spec.has("#expressions")) {
                declareExpressions(spec.get("#expressions"), "$.#expressions");
            }
            if (spec.size() > (spec.has("#expressions") ? 2 : 1)) {
                throw new SpecException("#chain can only be combined with #expressions", "$");
            }
            JsonNode stepsNode = spec.get("#chain");
            if (!stepsNode.isArray()) {
                throw new SpecException("#chain expects an array of specs", "$.#chain");
            }
            return compileChain((ArrayNode) stepsNode, "$.#chain");
        }
        return compileStep(spec, "$");
    }

    private Expr compileChain(ArrayNode stepsNode, String specPath) {
        if (stepsNode.isEmpty()) {
            throw new SpecException("a chain needs at least one spec", specPath);
        }
        List<Expr> steps = new ArrayList<>();
        for (int i = 0; i < stepsNode.size(); i++) {
            // each step has its own #let scope; #expressions are inherited but step-local
            // declarations must not leak into later steps
            Set<String> savedVars = Set.copyOf(knownVars);
            Map<String, JsonNode> savedCatalog = new LinkedHashMap<>(localCatalog);
            knownVars.clear();
            try {
                steps.add(compileStep(stepsNode.get(i), specPath + "[" + i + "]"));
            } finally {
                knownVars.clear();
                knownVars.addAll(savedVars);
                localCatalog.clear();
                localCatalog.putAll(savedCatalog);
            }
        }
        Expr[] arr = steps.toArray(new Expr[0]);
        return ctx -> {
            JsonNode doc = ctx.root();
            for (Expr step : arr) {
                if (doc == null) {
                    return null;
                }
                doc = step.eval(new Context(doc));
            }
            return doc;
        };
    }

    /** A spec root or chain step: handles {@code #expressions} and {@code #let}, then compiles the body. */
    private Expr compileStep(JsonNode spec, String specPath) {
        boolean hasExpressions = spec.isObject() && spec.has("#expressions");
        if (hasExpressions) {
            declareExpressions(spec.get("#expressions"), specPath + ".#expressions");
        }
        if (!(spec.isObject() && spec.has("#let"))) {
            if (!hasExpressions) {
                return compileExpr(spec, specPath);
            }
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            spec.fields().forEachRemaining(e -> {
                if (!e.getKey().equals("#expressions")) {
                    body.set(e.getKey(), e.getValue());
                }
            });
            return compileExpr(body, specPath);
        }
        JsonNode letNode = spec.get("#let");
        if (!letNode.isObject()) {
            throw new SpecException("#let expects an object of variable definitions", specPath + ".#let");
        }
        List<String> names = new ArrayList<>();
        List<Expr> defs = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = letNode.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String name = e.getKey();
            if (!isIdentifier(name)) {
                throw new SpecException("'" + name + "' is not a valid variable name "
                        + "(letters, digits and _ , starting with a letter or _)", specPath + ".#let");
            }
            defs.add(compileExpr(e.getValue(), specPath + ".#let." + name));
            names.add(name);
            knownVars.add(name); // later definitions and the body may reference it
        }
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = spec.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getKey().equals("#let") && !e.getKey().equals("#expressions")) {
                body.set(e.getKey(), e.getValue());
            }
        }
        Expr bodyExpr = compileExpr(body, specPath);
        String[] nameArr = names.toArray(new String[0]);
        Expr[] defArr = defs.toArray(new Expr[0]);
        return ctx -> {
            Map<String, JsonNode> vars = new LinkedHashMap<>();
            Context scoped = ctx.withVars(vars);
            for (int i = 0; i < nameArr.length; i++) {
                JsonNode v = defArr[i].eval(scoped);
                if (v != null) {
                    vars.put(nameArr[i], v);
                }
            }
            return bodyExpr.eval(scoped);
        };
    }

    // ------------------------------------------------------------------ expressions

    public Expr compileExpr(JsonNode spec, String specPath) {
        if (spec == null || spec.isMissingNode()) {
            throw new SpecException("expected a value", specPath);
        }
        if (spec.isTextual()) {
            return compileString(spec.textValue(), specPath);
        }
        if (spec.isValueNode()) {
            return ctx -> spec; // numbers, booleans, JSON null: literal
        }
        if (spec.isArray()) {
            return compileArrayTemplate((ArrayNode) spec, specPath);
        }
        return compileObject((ObjectNode) spec, specPath);
    }

    private Expr compileString(String text, String specPath) {
        if (!JsonPath.isPath(text)) {
            TextNode literal = TextNode.valueOf(text);
            return ctx -> literal;
        }
        JsonPath path;
        try {
            path = JsonPath.parse(text);
        } catch (IllegalArgumentException bad) {
            throw new SpecException(bad.getMessage(), specPath);
        }
        if (path.anchor() == JsonPath.Anchor.VAR && !knownVars.contains(path.varName())) {
            throw new SpecException("unknown variable '$" + path.varName() + "'; declare it with #let, "
                    + "or use {\"#literal\": ...} if this is meant as a literal string", specPath);
        }
        return path::resolve;
    }

    private Expr compileArrayTemplate(ArrayNode spec, String specPath) {
        List<Expr> children = new ArrayList<>();
        for (int i = 0; i < spec.size(); i++) {
            children.add(compileExpr(spec.get(i), specPath + "[" + i + "]"));
        }
        Expr[] arr = children.toArray(new Expr[0]);
        return ctx -> {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (Expr child : arr) {
                JsonNode v = child.eval(ctx);
                if (v != null) {
                    out.add(v);
                }
            }
            return out;
        };
    }

    private Expr compileObject(ObjectNode spec, String specPath) {
        List<String> opKeys = new ArrayList<>();
        boolean hasPlainKeys = false;
        for (Iterator<String> it = spec.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (key.startsWith("#")) {
                opKeys.add(key);
            } else {
                hasPlainKeys = true;
            }
        }
        if (opKeys.isEmpty() || (opKeys.size() == 1 && opKeys.get(0).equals("#spread"))) {
            return compileObjectTemplate(spec, specPath);
        }
        if (spec.has("#stream")) {
            if (hasPlainKeys) {
                throw new SpecException("a pipeline can only contain operation keys; "
                        + "move plain output keys to an enclosing object", specPath);
            }
            return Pipeline.compile(spec, specPath, this);
        }
        if (spec.has("#let")) {
            throw new SpecException("#let is only allowed at the root of a spec (or of a chain step)", specPath);
        }
        if (spec.has("#expressions")) {
            throw new SpecException("#expressions is only allowed at the root of a spec (or of a chain step)", specPath);
        }
        return compileOpExpression(spec, specPath, opKeys, hasPlainKeys);
    }

    /** Object templates: literal output structure, with optional {@code #spread}. */
    private Expr compileObjectTemplate(ObjectNode spec, String specPath) {
        record Entry(String key, Expr expr, boolean spread) {
        }
        List<Entry> entries = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = spec.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            String path = specPath + "." + key;
            if (key.equals("#spread")) {
                JsonNode arg = e.getValue();
                if (arg.isArray()) {
                    for (int i = 0; i < arg.size(); i++) {
                        entries.add(new Entry(null, compileExpr(arg.get(i), path + "[" + i + "]"), true));
                    }
                } else {
                    entries.add(new Entry(null, compileExpr(arg, path), true));
                }
            } else {
                String outKey = key.startsWith("\\#") ? key.substring(1) : key;
                entries.add(new Entry(outKey, compileExpr(e.getValue(), path), false));
            }
        }
        return ctx -> {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Entry entry : entries) {
                JsonNode v = entry.expr.eval(ctx);
                if (v == null) {
                    continue;
                }
                if (entry.spread) {
                    if (v.isObject()) {
                        v.fields().forEachRemaining(f -> out.set(f.getKey(), f.getValue()));
                    }
                } else {
                    out.set(entry.key, v);
                }
            }
            return out;
        };
    }

    // ------------------------------------------------------------------ operation expressions

    private Expr compileOpExpression(ObjectNode spec, String specPath, List<String> opKeys, boolean hasPlainKeys) {
        List<String> primaries = opKeys.stream().filter(k -> !SATELLITES.contains(k)).toList();
        if (primaries.isEmpty()) {
            throw new SpecException("found only " + opKeys + " — expected a primary operation "
                    + "such as #get, #if, #lookup or #stream", specPath);
        }
        if (primaries.size() > 1) {
            throw new SpecException("an expression can hold one operation, found " + primaries, specPath);
        }
        String primary = primaries.get(0);
        if (hasPlainKeys) {
            throw new SpecException("cannot mix plain output keys with operation '" + primary
                    + "'; wrap the operation in an object template value", specPath);
        }
        boolean standalone = STANDALONE_STREAM_OPS.contains(primary) && isDataArg(spec.get(primary));
        boolean known = standalone || VALUE_OPS.contains(primary) || functions.containsKey(primary.substring(1))
                || isExpressionOp(primary);
        if (!known) {
            if (Pipeline.STAGES.contains(primary) || Pipeline.TERMINALS.contains(primary)) {
                String hint = STANDALONE_STREAM_OPS.contains(primary)
                        ? ", or give it data directly: {\"" + primary + "\": \"$.items\"}"
                        : "";
                throw new SpecException("'" + primary + "' is a stream operation and needs a source — "
                        + "use it after \"#stream\" in a pipeline" + hint, specPath);
            }
            throw new SpecException(unknownOp(primary, this), specPath);
        }
        for (String satellite : SATELLITES) {
            if (!spec.has(satellite)) {
                continue;
            }
            boolean ok = switch (satellite) {
                case "#default" -> true;
                case "#on" -> primary.equals("#lookup");
                case "#then", "#else" -> primary.equals("#if");
                default -> false;
            };
            if (!ok) {
                throw new SpecException("'" + satellite + "' does not apply to '" + primary + "'", specPath);
            }
        }
        Expr expr = compilePrimary(primary, spec, specPath);
        if (spec.has("#default")) {
            Expr fallback = compileExpr(spec.get("#default"), specPath + ".#default");
            Expr inner = expr;
            return ctx -> {
                JsonNode v = inner.eval(ctx);
                return v != null ? v : fallback.eval(ctx);
            };
        }
        return expr;
    }

    private Expr compilePrimary(String op, ObjectNode spec, String specPath) {
        JsonNode arg = spec.get(op);
        String path = specPath + "." + op;
        if (STANDALONE_STREAM_OPS.contains(op) && isDataArg(arg)) {
            // standalone form: the argument is the data — desugar {"#count": src}
            // into {"#stream": src, "#count": "@"}
            if (arg.isTextual() && !JsonPath.isPath(arg.textValue())) {
                throw new SpecException("'" + arg.textValue() + "' is not a path; did you mean '$."
                        + arg.textValue() + "'?", path);
            }
            ObjectNode pipeline = JsonNodeFactory.instance.objectNode();
            pipeline.set("#stream", arg);
            pipeline.put(op, "@");
            return Pipeline.compile(pipeline, specPath, this);
        }
        ExpressionEngine engine = engines.get(op.substring(1));
        if (engine != null) {
            if (!arg.isTextual()) {
                throw new SpecException(op + " expects a code string", path);
            }
            return compileEngineExpr(op, engine, arg.textValue(), path);
        }
        if (op.equals("#expr")) {
            return compileExprReference(arg, path);
        }
        switch (op) {
            case "#get":
                return compileExpr(arg, path);
            case "#literal":
                return ctx -> arg;
            case "#if": {
                Matcher condition = Matcher.compile(arg, path, this);
                if (!spec.has("#then")) {
                    throw new SpecException("#if needs a #then branch", specPath);
                }
                Expr then = compileExpr(spec.get("#then"), specPath + ".#then");
                Expr otherwise = spec.has("#else")
                        ? compileExpr(spec.get("#else"), specPath + ".#else")
                        : ctx -> null;
                return ctx -> condition.test(ctx) ? then.eval(ctx) : otherwise.eval(ctx);
            }
            case "#lookup": {
                Expr table = compileExpr(arg, path);
                if (!spec.has("#on")) {
                    throw new SpecException("#lookup needs #on (the value to look up)", specPath);
                }
                Expr on = compileExpr(spec.get("#on"), specPath + ".#on");
                return ctx -> {
                    JsonNode tableNode = table.eval(ctx);
                    JsonNode key = on.eval(ctx);
                    if (tableNode == null || !tableNode.isObject() || key == null) {
                        return null;
                    }
                    return tableNode.get(key.isValueNode() ? key.asText() : key.toString());
                };
            }
            case "#concat": {
                if (!arg.isArray()) {
                    throw new SpecException("#concat expects an array of parts", path);
                }
                List<Expr> parts = new ArrayList<>();
                for (int i = 0; i < arg.size(); i++) {
                    parts.add(compileExpr(arg.get(i), path + "[" + i + "]"));
                }
                Expr[] arr = parts.toArray(new Expr[0]);
                return ctx -> {
                    StringBuilder sb = new StringBuilder();
                    for (Expr part : arr) {
                        JsonNode v = part.eval(ctx);
                        if (v != null && !v.isNull()) {
                            sb.append(v.isValueNode() ? v.asText() : v.toString());
                        }
                    }
                    return TextNode.valueOf(sb.toString());
                };
            }
            case "#upper":
            case "#lower":
            case "#trim": {
                Expr inner = compileExpr(arg, path);
                return ctx -> {
                    JsonNode v = inner.eval(ctx);
                    if (v == null || v.isNull()) {
                        return null;
                    }
                    String s = v.isValueNode() ? v.asText() : v.toString();
                    return TextNode.valueOf(switch (op) {
                        case "#upper" -> s.toUpperCase();
                        case "#lower" -> s.toLowerCase();
                        default -> s.trim();
                    });
                };
            }
            case "#replace": {
                if (!arg.isObject() || !arg.has("#in") || !arg.has("#find") || !arg.has("#with")) {
                    throw new SpecException("#replace expects {\"#in\": ..., \"#find\": ..., \"#with\": ...}", path);
                }
                Expr in = compileExpr(arg.get("#in"), path + ".#in");
                Expr find = compileExpr(arg.get("#find"), path + ".#find");
                Expr with = compileExpr(arg.get("#with"), path + ".#with");
                return ctx -> {
                    JsonNode target = in.eval(ctx);
                    JsonNode f = find.eval(ctx);
                    JsonNode w = with.eval(ctx);
                    if (target == null || target.isNull() || f == null || w == null) {
                        return null;
                    }
                    String s = target.isValueNode() ? target.asText() : target.toString();
                    return TextNode.valueOf(s.replace(f.asText(), w.asText()));
                };
            }
            case "#substring": {
                if (!arg.isObject() || !arg.has("#of")) {
                    throw new SpecException("#substring expects {\"#of\": ..., \"#start\": n, \"#end\": n}", path);
                }
                for (Iterator<String> it = arg.fieldNames(); it.hasNext(); ) {
                    String k = it.next();
                    if (!k.equals("#of") && !k.equals("#start") && !k.equals("#end")) {
                        throw new SpecException("unknown #substring option '" + k + "'", path);
                    }
                }
                Expr of = compileExpr(arg.get("#of"), path + ".#of");
                Integer start = optionalInt(arg, "#start", path);
                Integer end = optionalInt(arg, "#end", path);
                return ctx -> {
                    JsonNode v = of.eval(ctx);
                    if (v == null || v.isNull()) {
                        return null;
                    }
                    String s = v.isValueNode() ? v.asText() : v.toString();
                    int len = s.length();
                    int from = clampIndex(start == null ? 0 : start, len);
                    int to = clampIndex(end == null ? len : end, len);
                    return TextNode.valueOf(from >= to ? "" : s.substring(from, to));
                };
            }
            case "#toString": {
                Expr inner = compileExpr(arg, path);
                return ctx -> {
                    JsonNode v = inner.eval(ctx);
                    if (v == null) {
                        return null;
                    }
                    return TextNode.valueOf(v.isValueNode() && !v.isNull() ? v.asText() : v.toString());
                };
            }
            case "#toNumber": {
                Expr inner = compileExpr(arg, path);
                return ctx -> {
                    JsonNode v = inner.eval(ctx);
                    if (v == null) {
                        return null;
                    }
                    if (v.isNumber()) {
                        return v;
                    }
                    if (v.isTextual()) {
                        try {
                            return com.jsonweave.ops.JsonCompare.numberNode(new BigDecimal(v.textValue().trim()));
                        } catch (NumberFormatException notANumber) {
                            return null;
                        }
                    }
                    return null;
                };
            }
            case "#entries": {
                Expr inner = compileExpr(arg, path);
                return ctx -> {
                    JsonNode v = inner.eval(ctx);
                    if (v == null || !v.isObject()) {
                        return null;
                    }
                    ArrayNode out = JsonNodeFactory.instance.arrayNode();
                    v.fields().forEachRemaining(f -> {
                        ObjectNode entry = out.addObject();
                        entry.put("key", f.getKey());
                        entry.set("value", f.getValue());
                    });
                    return out;
                };
            }
            default: { // registered custom function, standalone form
                WeaveFunction fn = Objects.requireNonNull(functions.get(op.substring(1)));
                Expr argExpr = compileExpr(arg, path);
                String name = op;
                return ctx -> {
                    JsonNode v = argExpr.eval(ctx);
                    List<JsonNode> input;
                    if (v == null) {
                        input = List.of();
                    } else if (v.isArray()) {
                        List<JsonNode> list = new ArrayList<>(v.size());
                        v.forEach(list::add);
                        input = list;
                    } else {
                        input = List.of(v);
                    }
                    try {
                        return fn.apply(input, arg);
                    } catch (RuntimeException ex) {
                        throw new TransformException("custom function '" + name + "' failed: " + ex.getMessage(),
                                path, ex);
                    }
                };
            }
        }
    }

    // ------------------------------------------------------------------ expression engines & catalogs

    /** Registers the entries of a root-level {@code #expressions} block into the spec-local catalog. */
    private void declareExpressions(JsonNode node, String path) {
        if (!node.isObject()) {
            throw new SpecException("#expressions expects an object of named expressions", path);
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!isIdentifier(e.getKey())) {
                throw new SpecException("'" + e.getKey() + "' is not a valid expression name "
                        + "(letters, digits and _ , starting with a letter or _)", path);
            }
            localCatalog.put(e.getKey(), e.getValue());
        }
    }

    /** Resolves {@code {"#expr": "name"}} against the catalogs and picks a dialect by engine registration order. */
    private Expr compileExprReference(JsonNode arg, String path) {
        if (!arg.isTextual()) {
            throw new SpecException("#expr expects the name of a catalog expression", path);
        }
        String name = arg.textValue();
        JsonNode entry = localCatalog.containsKey(name) ? localCatalog.get(name) : externalCatalog.get(name);
        if (entry == null) {
            List<String> names = new ArrayList<>(localCatalog.keySet());
            names.addAll(externalCatalog.keySet());
            String best = nearest(name, names);
            throw new SpecException("unknown expression '" + name + "'"
                    + (best != null ? " (did you mean '" + best + "'?)" : "")
                    + "; declare it in an expression catalog or a root #expressions block", path);
        }
        if (engines.isEmpty()) {
            throw new SpecException("no expression engines are registered; "
                    + "register one (e.g. MVEL or JS) to use #expr", path);
        }
        String label;
        ExpressionEngine engine;
        String source;
        if (entry.isTextual()) {
            Map.Entry<String, ExpressionEngine> first = engines.entrySet().iterator().next();
            label = "#" + first.getKey() + " expression '" + name + "'";
            engine = first.getValue();
            source = entry.textValue();
        } else if (entry.isObject()) {
            Map.Entry<String, ExpressionEngine> chosen = null;
            for (Map.Entry<String, ExpressionEngine> candidate : engines.entrySet()) {
                if (entry.has(candidate.getKey())) {
                    chosen = candidate;
                    break;
                }
            }
            if (chosen == null) {
                List<String> offered = new ArrayList<>();
                entry.fieldNames().forEachRemaining(offered::add);
                throw new SpecException("expression '" + name + "' offers dialects " + offered
                        + " but the registered engines are " + engines.keySet(), path);
            }
            JsonNode code = entry.get(chosen.getKey());
            if (!code.isTextual()) {
                throw new SpecException("expression '" + name + "', dialect '" + chosen.getKey()
                        + "': expected a code string", path);
            }
            label = "#" + chosen.getKey() + " expression '" + name + "'";
            engine = chosen.getValue();
            source = code.textValue();
        } else {
            throw new SpecException("expression '" + name
                    + "' must be a code string or an object of dialects", path);
        }
        return compileEngineExpr(label, engine, source, path);
    }

    /** Compiles engine source once; wraps eval-time failures as TransformExceptions carrying the spec path. */
    private Expr compileEngineExpr(String label, ExpressionEngine engine, String source, String path) {
        Object compiled;
        try {
            compiled = engine.compile(source);
        } catch (RuntimeException bad) {
            throw new SpecException(label + " has a syntax error: " + bad.getMessage(), path);
        }
        return ctx -> {
            try {
                return engine.eval(compiled, ctx);
            } catch (TransformException te) {
                throw te;
            } catch (RuntimeException ex) {
                throw new TransformException(label + " failed: " + ex.getMessage(), path, ex);
            }
        };
    }

    // ------------------------------------------------------------------ helpers

    /** Whether a standalone-form argument denotes data, as opposed to the in-pipeline marker {@code "@"} or a boolean. */
    private static boolean isDataArg(JsonNode arg) {
        return !arg.isBoolean() && !(arg.isTextual() && arg.textValue().equals("@"));
    }

    private static Integer optionalInt(JsonNode obj, String key, String path) {
        JsonNode v = obj.get(key);
        if (v == null) {
            return null;
        }
        if (!v.isIntegralNumber()) {
            throw new SpecException(key + " expects an integer", path);
        }
        return v.intValue();
    }

    private static int clampIndex(int idx, int len) {
        if (idx < 0) {
            idx = len + idx;
        }
        return Math.max(0, Math.min(idx, len));
    }

    private static boolean isIdentifier(String s) {
        if (s.isEmpty() || !JsonPath.isIdentStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!JsonPath.isIdentPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Builds the "unknown operation" message with a nearest-name suggestion. */
    static String unknownOp(String key, SpecCompiler compiler) {
        List<String> candidates = new ArrayList<>(ALL_BUILTIN_OPS);
        compiler.functions.keySet().forEach(n -> candidates.add("#" + n));
        compiler.engines.keySet().forEach(n -> candidates.add("#" + n));
        String best = nearest(key, candidates);
        return "unknown operation '" + key + "'" + (best != null ? " (did you mean '" + best + "'?)" : "");
    }

    /** Nearest candidate within edit distance 2, or null. */
    private static String nearest(String target, java.util.Collection<String> candidates) {
        String best = null;
        int bestDist = 3;
        for (String candidate : candidates) {
            int d = levenshtein(target, candidate, bestDist);
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        return best;
    }

    private static int levenshtein(String a, String b, int cap) {
        if (Math.abs(a.length() - b.length()) >= cap) {
            return cap;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
