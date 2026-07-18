package com.jsonweave.js;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jsonweave.ExpressionEngine;
import com.jsonweave.Jsonweave;
import com.jsonweave.spec.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.Proxy;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The portable expression dialect: JavaScript via GraalJS, <b>sandboxed by default</b> —
 * no host access, no IO, no {@code Java.type}. Register as:
 *
 * <pre>{@code
 * Jsonweave.builder().registerExpressionEngine("js", new JsEngine())...
 * }</pre>
 *
 * <p>Bindings: {@code root}, {@code it} and every {@code #let} variable by plain name.
 * Documents are exposed through lazy proxies, so only the properties an expression
 * touches cross the language boundary.
 *
 * <p>This is the dialect future Node/Python Jsonweave runtimes will execute natively.
 * On stock JDKs GraalJS runs in interpreter mode — correct but slower than {@code #mvel};
 * prefer built-ins, then {@code #mvel}, for JVM-hot paths.
 */
public final class JsEngine implements ExpressionEngine {

    private static final AtomicLong IDS = new AtomicLong();

    private final Engine engine = Engine.newBuilder()
            .option("engine.WarnInterpreterOnly", "false")
            .build();

    /** Graal contexts are single-threaded; keep one per thread, parsed functions cached per context. */
    private final ThreadLocal<ContextState> state = ThreadLocal.withInitial(ContextState::new);

    private final class ContextState {
        final org.graalvm.polyglot.Context context = org.graalvm.polyglot.Context.newBuilder("js")
                .engine(engine)
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(className -> false)
                .build();
        final Map<Compiled, Value> functions = new HashMap<>();

        Value function(Compiled compiled) {
            return functions.computeIfAbsent(compiled, c -> context.parse(c.source).execute());
        }
    }

    /** A compiled expression: the wrapped source, identity-hashed for per-context caches. */
    private static final class Compiled {
        final Source source;

        Compiled(Source source) {
            this.source = source;
        }
    }

    /** Convenience: a builder with this engine registered under the conventional name. */
    public static Jsonweave.Builder builder() {
        return Jsonweave.builder().registerExpressionEngine("js", new JsEngine());
    }

    @Override
    public Object compile(String expression) {
        // bind root/it as arguments and #let variables via `with`, and force expression position
        String wrapped = "(function(root, it, __vars) { with (__vars) { return (" + expression + "\n); } })";
        Source source = Source.newBuilder("js", wrapped, "jsonweave-expr-" + IDS.incrementAndGet())
                .cached(true)
                .buildLiteral();
        try {
            Compiled compiled = new Compiled(source);
            state.get().function(compiled); // parse eagerly: surface syntax errors at spec-compile time
            return compiled;
        } catch (PolyglotException bad) {
            throw new IllegalArgumentException(bad.getMessage(), bad);
        }
    }

    @Override
    public JsonNode eval(Object compiled, Context ctx) {
        ContextState s = state.get();
        Value fn = s.function((Compiled) compiled);
        Map<String, Object> vars = new HashMap<>();
        for (Map.Entry<String, JsonNode> var : ctx.vars().entrySet()) {
            vars.put(var.getKey(), wrap(var.getValue()));
        }
        Value result = fn.execute(wrap(ctx.root()), wrap(ctx.currentOrRoot()), ProxyObject.fromMap(vars));
        return toNode(result);
    }

    // ------------------------------------------------------------------ data across the boundary

    /** JsonNode -> guest value: scalars as primitives, containers as lazy proxies. */
    private static Object wrap(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (n.isObject()) {
            return new NodeObject((ObjectNode) n);
        }
        if (n.isArray()) {
            return new NodeArray((ArrayNode) n);
        }
        if (n.isTextual()) {
            return n.textValue();
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isIntegralNumber() && n.canConvertToLong()) {
            return n.longValue();
        }
        if (n.isNumber()) {
            return n.doubleValue();
        }
        return n.asText();
    }

    private record NodeObject(ObjectNode node) implements ProxyObject {
        @Override
        public Object getMember(String key) {
            return wrap(node.get(key));
        }

        @Override
        public Object getMemberKeys() {
            List<String> keys = new ArrayList<>(node.size());
            node.fieldNames().forEachRemaining(keys::add);
            return keys;
        }

        @Override
        public boolean hasMember(String key) {
            return node.has(key);
        }

        @Override
        public void putMember(String key, Value value) {
            throw new UnsupportedOperationException("jsonweave documents are read-only");
        }
    }

    private record NodeArray(ArrayNode node) implements ProxyArray {
        @Override
        public Object get(long index) {
            return wrap(node.get((int) index));
        }

        @Override
        public long getSize() {
            return node.size();
        }

        @Override
        public void set(long index, Value value) {
            throw new UnsupportedOperationException("jsonweave documents are read-only");
        }
    }

    /** Guest value -> JsonNode; null/undefined -> missing. */
    private static JsonNode toNode(Value v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isProxyObject()) {
            Proxy p = v.asProxyObject();
            if (p instanceof NodeObject o) {
                return o.node();
            }
            if (p instanceof NodeArray a) {
                return a.node();
            }
        }
        if (v.isBoolean()) {
            return BooleanNode.valueOf(v.asBoolean());
        }
        if (v.isNumber()) {
            if (v.fitsInInt()) {
                return IntNode.valueOf(v.asInt());
            }
            if (v.fitsInLong()) {
                return LongNode.valueOf(v.asLong());
            }
            return DoubleNode.valueOf(v.asDouble());
        }
        if (v.isString()) {
            return TextNode.valueOf(v.asString());
        }
        if (v.hasArrayElements()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (long i = 0; i < v.getArraySize(); i++) {
                JsonNode child = toNode(v.getArrayElement(i));
                out.add(child != null ? child : com.fasterxml.jackson.databind.node.NullNode.getInstance());
            }
            return out;
        }
        if (v.hasMembers()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (String key : v.getMemberKeys()) {
                JsonNode child = toNode(v.getMember(key));
                if (child != null) {
                    out.set(key, child);
                }
            }
            return out;
        }
        return TextNode.valueOf(v.toString());
    }
}
