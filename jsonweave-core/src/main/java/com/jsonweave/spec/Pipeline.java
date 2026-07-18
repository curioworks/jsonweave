package com.jsonweave.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jsonweave.SpecException;
import com.jsonweave.TransformException;
import com.jsonweave.WeaveFunction;
import com.jsonweave.ops.JsonCompare;
import com.jsonweave.ops.Matcher;
import com.jsonweave.path.JsonPath;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A compiled {@code #stream} pipeline: Java Streams expressed in JSON. Stages are fused
 * into a single lazy {@link Stream} chain -- {@code #filter}+{@code #map}+{@code #limit}
 * run in one pass, and short-circuiting terminals ({@code #first}, {@code #anyMatch})
 * stop consuming the source early. Only {@code #sortBy}, {@code #reverse} and custom
 * functions materialize.
 */
final class Pipeline implements Expr {

    static final Set<String> STAGES = Set.of(
            "#filter", "#map", "#flatMap", "#sortBy", "#distinct", "#skip", "#limit", "#reverse");
    static final Set<String> TERMINALS = Set.of(
            "#first", "#last", "#count", "#sum", "#min", "#max", "#avg", "#join",
            "#groupBy", "#anyMatch", "#allMatch", "#noneMatch", "#toObject");

    /** A stage: returns the continuing {@link Stream}, or a {@link JsonNode} when a custom function ends the pipeline. */
    @FunctionalInterface
    private interface Step {
        Object apply(Stream<JsonNode> in, Context ctx);
    }

    @FunctionalInterface
    private interface Terminal {
        JsonNode collect(Stream<JsonNode> in, Context ctx);
    }

    /** Extracts a per-element key ({@code #sortBy}, {@code #sum}, {@code #groupBy}, ...). */
    @FunctionalInterface
    private interface Extractor {
        JsonNode extract(JsonNode element, Context ctx);
    }

    private final Expr source;
    private final Step[] steps;
    private final Terminal terminal;   // null -> collect to array
    private final Expr defaultExpr;    // null -> missing stays missing
    private final String specPath;

    private Pipeline(Expr source, Step[] steps, Terminal terminal, Expr defaultExpr, String specPath) {
        this.source = source;
        this.steps = steps;
        this.terminal = terminal;
        this.defaultExpr = defaultExpr;
        this.specPath = specPath;
    }

    @Override
    public JsonNode eval(Context ctx) {
        JsonNode src = source.eval(ctx);
        JsonNode result;
        if (src == null) {
            result = null;
        } else {
            Object cur = toStream(src);
            for (Step step : steps) {
                if (!(cur instanceof Stream)) {
                    throw new TransformException(
                            "a custom function ended the pipeline with a non-array value, but further stages follow",
                            specPath);
                }
                @SuppressWarnings("unchecked")
                Stream<JsonNode> stream = (Stream<JsonNode>) cur;
                cur = step.apply(stream, ctx);
            }
            if (cur instanceof Stream) {
                @SuppressWarnings("unchecked")
                Stream<JsonNode> stream = (Stream<JsonNode>) cur;
                result = terminal != null ? terminal.collect(stream, ctx) : collectArray(stream);
            } else {
                if (terminal != null) {
                    throw new TransformException(
                            "a custom function ended the pipeline with a non-array value, but a terminal follows",
                            specPath);
                }
                result = cur instanceof MissingNode ? null : (JsonNode) cur;
            }
        }
        if (result == null && defaultExpr != null) {
            result = defaultExpr.eval(ctx);
        }
        return result;
    }

    private static Stream<JsonNode> toStream(JsonNode src) {
        return src.isArray() ? StreamSupport.stream(src.spliterator(), false) : Stream.of(src);
    }

    private static ArrayNode collectArray(Stream<JsonNode> s) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        s.forEach(out::add);
        return out;
    }

    // ------------------------------------------------------------------ compile

    static Pipeline compile(JsonNode spec, String specPath, SpecCompiler compiler) {
        JsonNode sourceArg = spec.get("#stream");
        if (sourceArg.isTextual() && !JsonPath.isPath(sourceArg.textValue())) {
            throw new SpecException("'" + sourceArg.textValue() + "' is not a path; did you mean '$."
                    + sourceArg.textValue() + "'?", specPath + ".#stream");
        }
        Expr source = compiler.compileExpr(sourceArg, specPath + ".#stream");
        List<Step> steps = new ArrayList<>();
        Terminal terminal = null;
        Expr defaultExpr = null;
        boolean terminalSeen = false;

        for (Iterator<Map.Entry<String, JsonNode>> it = spec.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            if (key.equals("#stream")) {
                continue;
            }
            JsonNode arg = e.getValue();
            String path = specPath + "." + key;
            if (key.equals("#default")) {
                defaultExpr = compiler.compileExpr(arg, path);
                continue;
            }
            if (!key.startsWith("#")) {
                throw new SpecException("a pipeline can only contain operation keys; found data key '"
                        + key + "' (did you mean to wrap this pipeline in an object template?)", specPath);
            }
            if (terminalSeen) {
                throw new SpecException("'" + key + "' appears after a terminal operation; "
                        + "a terminal must be the last operation in a pipeline", path);
            }
            if (STAGES.contains(key)) {
                steps.add(compileStage(key, arg, path, compiler));
            } else if (TERMINALS.contains(key)) {
                terminal = compileTerminal(key, arg, path, compiler);
                terminalSeen = true;
            } else {
                if (compiler.isExpressionOp(key)) {
                    throw new SpecException(key + " is not a pipeline stage; use it inside \"#map\" "
                            + "(per-element value) or \"#filter\" (predicate)", path);
                }
                String name = key.substring(1);
                WeaveFunction fn = compiler.functions().get(name);
                if (fn == null) {
                    throw new SpecException(SpecCompiler.unknownOp(key, compiler), path);
                }
                steps.add(customStep(name, fn, arg, path));
            }
        }
        return new Pipeline(source, steps.toArray(new Step[0]), terminal, defaultExpr, specPath);
    }

    private static Step customStep(String name, WeaveFunction fn, JsonNode arg, String path) {
        return (in, ctx) -> {
            List<JsonNode> list = new ArrayList<>();
            in.forEach(list::add);
            JsonNode result;
            try {
                result = fn.apply(Collections.unmodifiableList(list), arg);
            } catch (RuntimeException ex) {
                throw new TransformException("custom function '#" + name + "' failed: " + ex.getMessage(), path, ex);
            }
            if (result == null) {
                return MissingNode.getInstance();
            }
            return result.isArray() ? toStream(result) : result;
        };
    }

    private static Step compileStage(String key, JsonNode arg, String path, SpecCompiler compiler) {
        switch (key) {
            case "#filter": {
                Matcher m = Matcher.compile(arg, path, compiler);
                return (in, ctx) -> in.filter(e -> m.test(ctx.withCurrent(e)));
            }
            case "#map": {
                Expr template = compileElementExpr(arg, path, compiler);
                return (in, ctx) -> in.map(e -> template.eval(ctx.withCurrent(e))).filter(java.util.Objects::nonNull);
            }
            case "#flatMap": {
                // "@" maps each element to itself, which flattens nested arrays one level
                Expr template = compileElementExpr(arg, path, compiler);
                return (in, ctx) -> in.flatMap(e -> {
                    JsonNode v = template.eval(ctx.withCurrent(e));
                    return v == null ? Stream.empty() : toStream(v);
                });
            }
            case "#sortBy": {
                SortKey[] keys = compileSortKeys(arg, path);
                // decorate-sort-undecorate: extract each element's keys once, not per comparison
                return (in, ctx) -> {
                    List<JsonNode[]> rows = new ArrayList<>();
                    in.forEach(e -> {
                        JsonNode[] row = new JsonNode[keys.length + 1];
                        row[0] = e;
                        for (int i = 0; i < keys.length; i++) {
                            row[i + 1] = keys[i].extractor.extract(e, ctx);
                        }
                        rows.add(row);
                    });
                    rows.sort((a, b) -> {
                        for (int i = 0; i < keys.length; i++) {
                            JsonNode ka = a[i + 1];
                            JsonNode kb = b[i + 1];
                            int c;
                            if (ka == null || kb == null) {
                                c = (ka == null ? 1 : 0) - (kb == null ? 1 : 0); // missing sorts last
                            } else {
                                c = keys[i].descending ? JsonCompare.compare(kb, ka) : JsonCompare.compare(ka, kb);
                            }
                            if (c != 0) {
                                return c;
                            }
                        }
                        return 0;
                    });
                    return rows.stream().map(row -> row[0]);
                };
            }
            case "#distinct": {
                requireElementArg(arg, key, path);
                return (in, ctx) -> {
                    Set<Object> seen = new HashSet<>();
                    return in.filter(e -> seen.add(JsonCompare.canonicalKey(e)));
                };
            }
            case "#skip": {
                long n = requireCount(arg, key, path);
                return (in, ctx) -> in.skip(n);
            }
            case "#limit": {
                long n = requireCount(arg, key, path);
                return (in, ctx) -> in.limit(n);
            }
            case "#reverse": {
                requireElementArg(arg, key, path);
                return (in, ctx) -> {
                    List<JsonNode> list = new ArrayList<>();
                    in.forEach(list::add);
                    Collections.reverse(list);
                    return list.stream();
                };
            }
            default:
                throw new IllegalStateException(key);
        }
    }

    /**
     * {@code #map}/{@code #flatMap} string arguments must be explicit paths -- a bare
     * string here is almost always a mistake, so it fails at compile time instead of
     * silently producing a constant.
     */
    private static Expr compileElementExpr(JsonNode arg, String path, SpecCompiler compiler) {
        if (arg.isTextual() && !JsonPath.isPath(arg.textValue())) {
            throw new SpecException("'" + arg.textValue() + "' is not a path; use '@." + arg.textValue()
                    + "' for an element field, or {\"#literal\": ...} for a constant", path);
        }
        return compiler.compileExpr(arg, path);
    }

    private static Terminal compileTerminal(String key, JsonNode arg, String path, SpecCompiler compiler) {
        switch (key) {
            case "#first": {
                requireElementArg(arg, key, path);
                return (in, ctx) -> in.findFirst().orElse(null);
            }
            case "#last": {
                requireElementArg(arg, key, path);
                return (in, ctx) -> in.reduce((a, b) -> b).orElse(null);
            }
            case "#count": {
                requireElementArg(arg, key, path);
                return (in, ctx) -> {
                    long n = in.count();
                    return n == (int) n ? IntNode.valueOf((int) n) : LongNode.valueOf(n);
                };
            }
            case "#sum":
            case "#avg": {
                Extractor ex = compileExtractor(arg, key, path);
                boolean avg = key.equals("#avg");
                return (in, ctx) -> {
                    BigDecimal[] acc = {BigDecimal.ZERO};
                    long[] count = {0};
                    in.forEach(e -> {
                        JsonNode v = ex.extract(e, ctx);
                        if (v != null && v.isNumber()) {
                            acc[0] = acc[0].add(v.decimalValue());
                            count[0]++;
                        }
                    });
                    if (avg) {
                        if (count[0] == 0) {
                            return null;
                        }
                        return JsonCompare.numberNode(
                                acc[0].divide(BigDecimal.valueOf(count[0]), MathContext.DECIMAL64));
                    }
                    return JsonCompare.numberNode(acc[0]);
                };
            }
            case "#min":
            case "#max": {
                Extractor ex = compileExtractor(arg, key, path);
                int wanted = key.equals("#min") ? -1 : 1;
                return (in, ctx) -> {
                    Optional<JsonNode> best = in
                            .map(e -> ex.extract(e, ctx))
                            .filter(java.util.Objects::nonNull)
                            .reduce((a, b) -> Integer.signum(JsonCompare.compare(b, a)) == wanted ? b : a);
                    return best.orElse(null);
                };
            }
            case "#join": {
                if (!arg.isTextual()) {
                    throw new SpecException("#join expects a separator string", path);
                }
                String sep = arg.textValue();
                return (in, ctx) -> {
                    StringJoiner j = new StringJoiner(sep);
                    in.forEach(e -> j.add(stringify(e)));
                    return TextNode.valueOf(j.toString());
                };
            }
            case "#groupBy": {
                Extractor ex = compileExtractor(arg, key, path);
                return (in, ctx) -> {
                    ObjectNode groups = JsonNodeFactory.instance.objectNode();
                    in.forEach(e -> {
                        JsonNode k = ex.extract(e, ctx);
                        if (k == null) {
                            return;
                        }
                        String name = stringify(k);
                        JsonNode bucket = groups.get(name);
                        ArrayNode arr = bucket instanceof ArrayNode a ? a : groups.putArray(name);
                        arr.add(e);
                    });
                    return groups;
                };
            }
            case "#anyMatch":
            case "#allMatch":
            case "#noneMatch": {
                Matcher m = Matcher.compile(arg, path, compiler);
                return (in, ctx) -> {
                    boolean r = switch (key) {
                        case "#anyMatch" -> in.anyMatch(e -> m.test(ctx.withCurrent(e)));
                        case "#allMatch" -> in.allMatch(e -> m.test(ctx.withCurrent(e)));
                        default -> in.noneMatch(e -> m.test(ctx.withCurrent(e)));
                    };
                    return BooleanNode.valueOf(r);
                };
            }
            case "#toObject": {
                if (!arg.isObject() || !arg.has("#key") || !arg.has("#value")) {
                    throw new SpecException("#toObject expects {\"#key\": ..., \"#value\": ...} "
                            + "(per-element expressions)", path);
                }
                for (Iterator<String> it = arg.fieldNames(); it.hasNext(); ) {
                    String option = it.next();
                    if (!option.equals("#key") && !option.equals("#value")) {
                        throw new SpecException("unknown #toObject option '" + option
                                + "' (expected #key, #value)", path);
                    }
                }
                Expr keyExpr = compileElementExpr(arg.get("#key"), path + ".#key", compiler);
                Expr valueExpr = compileElementExpr(arg.get("#value"), path + ".#value", compiler);
                return (in, ctx) -> {
                    ObjectNode out = JsonNodeFactory.instance.objectNode();
                    in.forEach(e -> {
                        Context elemCtx = ctx.withCurrent(e);
                        JsonNode k = keyExpr.eval(elemCtx);
                        if (k == null || k.isNull()) {
                            return; // no key, no entry
                        }
                        if (!k.isValueNode()) {
                            throw new TransformException("#key produced " + k
                                    + "; object keys must be scalar values", path);
                        }
                        JsonNode v = valueExpr.eval(elemCtx);
                        if (v != null) {
                            out.set(k.asText(), v); // duplicate keys: last one wins
                        }
                    });
                    return out;
                };
            }
            default:
                throw new IllegalStateException(key);
        }
    }

    private record SortKey(Extractor extractor, boolean descending) {
    }

    private static SortKey[] compileSortKeys(JsonNode arg, String path) {
        List<SortKey> keys = new ArrayList<>();
        if (arg.isArray()) {
            for (int i = 0; i < arg.size(); i++) {
                keys.add(compileSortKey(arg.get(i), path + "[" + i + "]"));
            }
            if (keys.isEmpty()) {
                throw new SpecException("#sortBy key list is empty", path);
            }
        } else {
            keys.add(compileSortKey(arg, path));
        }
        return keys.toArray(new SortKey[0]);
    }

    private static SortKey compileSortKey(JsonNode arg, String path) {
        if (arg.isTextual()) {
            return new SortKey(pathExtractor(arg.textValue(), path), false);
        }
        if (arg.isObject()) {
            Extractor ex = (e, c) -> e;
            boolean desc = false;
            for (Iterator<Map.Entry<String, JsonNode>> it = arg.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                switch (e.getKey()) {
                    case "#path" -> {
                        if (!e.getValue().isTextual()) {
                            throw new SpecException("#path expects a path string", path);
                        }
                        ex = pathExtractor(e.getValue().textValue(), path);
                    }
                    case "#order" -> {
                        String o = e.getValue().asText();
                        if (!o.equals("asc") && !o.equals("desc")) {
                            throw new SpecException("#order must be \"asc\" or \"desc\"", path);
                        }
                        desc = o.equals("desc");
                    }
                    default -> throw new SpecException("unknown #sortBy option '" + e.getKey()
                            + "' (expected #path, #order)", path);
                }
            }
            return new SortKey(ex, desc);
        }
        throw new SpecException(
                "#sortBy expects a path (\"@.total\", or \"@\" for the element itself), "
                        + "{\"#path\", \"#order\"}, or an array of those", path);
    }

    /** Argument that is inherently a per-element key: a path, with {@code "@"} meaning the element itself. */
    private static Extractor compileExtractor(JsonNode arg, String op, String path) {
        if (arg.isTextual()) {
            return pathExtractor(arg.textValue(), path);
        }
        throw new SpecException(op + " expects a path (\"@.total\" for a field of each element, "
                + "or \"@\" for the element itself)", path);
    }

    private static Extractor pathExtractor(String raw, String specPath) {
        JsonPath p;
        try {
            p = JsonPath.parseKeyPath(raw);
        } catch (IllegalArgumentException bad) {
            throw new SpecException(bad.getMessage(), specPath);
        }
        return (e, ctx) -> p.resolve(ctx.withCurrent(e));
    }

    /** In-pipeline ops with nothing left to configure take {@code "@"} -- the elements themselves. */
    private static void requireElementArg(JsonNode arg, String op, String path) {
        if (!(arg.isTextual() && arg.textValue().equals("@"))) {
            throw new SpecException(op + " operates on the stream itself; write \"" + op + "\": \"@\"", path);
        }
    }

    private static long requireCount(JsonNode arg, String op, String path) {
        if (!arg.isIntegralNumber() || arg.longValue() < 0) {
            throw new SpecException(op + " expects a non-negative integer", path);
        }
        return arg.longValue();
    }

    private static String stringify(JsonNode n) {
        if (n.isNull()) {
            return "null";
        }
        return n.isValueNode() ? n.asText() : n.toString();
    }
}
