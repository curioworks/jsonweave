package com.jsonweave.mvel;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsonweave.ExpressionEngine;
import com.jsonweave.Jsonweave;
import com.jsonweave.spec.Context;
import org.mvel2.MVEL;

import java.util.HashMap;
import java.util.Map;

/**
 * The JVM performance expression dialect: MVEL, compiled once at spec-compile time and
 * evaluated directly against the document tree through lazy views (no per-element
 * conversion). Register as:
 *
 * <pre>{@code
 * Jsonweave.builder().registerExpressionEngine("mvel", new MvelEngine())...
 * }</pre>
 *
 * <p>Bindings: {@code root} (input document), {@code it} (current element, falling back
 * to the root outside element scopes) and every {@code #let} variable by its plain name.
 *
 * <p><b>Security:</b> MVEL can invoke arbitrary Java. Only compile specs from trusted
 * authors; never expose an MVEL-enabled endpoint publicly. This dialect is JVM-only —
 * for portable specs use the {@code js} dialect or built-in operations.
 */
public final class MvelEngine implements ExpressionEngine {

    /** Convenience: a builder with this engine registered under the conventional name. */
    public static Jsonweave.Builder builder() {
        return Jsonweave.builder().registerExpressionEngine("mvel", new MvelEngine());
    }

    @Override
    public Object compile(String expression) {
        try {
            return MVEL.compileExpression(expression);
        } catch (RuntimeException bad) {
            throw new IllegalArgumentException(bad.getMessage(), bad);
        }
    }

    @Override
    public JsonNode eval(Object compiled, Context ctx) {
        Map<String, Object> bindings = new HashMap<>();
        for (Map.Entry<String, JsonNode> var : ctx.vars().entrySet()) {
            bindings.put(var.getKey(), JsonViews.unwrap(var.getValue()));
        }
        bindings.put("root", JsonViews.unwrap(ctx.root()));
        bindings.put("it", JsonViews.unwrap(ctx.currentOrRoot()));
        return JsonViews.toNode(MVEL.executeExpression(compiled, bindings));
    }
}
