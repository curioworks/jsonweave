package com.jsonweave;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsonweave.spec.Context;

/**
 * A pluggable expression dialect, registered via
 * {@link Jsonweave.Builder#registerExpressionEngine(String, ExpressionEngine)} and invoked
 * from specs as {@code {"#name": "<code>"}} — or indirectly through named catalog entries
 * referenced with {@code {"#expr": "entryName"}}.
 *
 * <p>Engines receive the evaluation {@link Context} so expressions can see the input
 * document root, the current element inside per-element scopes, and {@code #let} variables.
 * The conventional bindings are {@code root}, {@code it} and each variable's plain name.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #compile} runs once at {@link Jsonweave#compile} time; throw
 *       {@link IllegalArgumentException} for syntax errors (surfaced as {@link SpecException}
 *       with the spec path).</li>
 *   <li>{@link #eval} must be thread-safe across concurrent transforms. Returning
 *       {@code null} means <em>missing</em> (the output key is omitted); runtime failures may
 *       throw any {@link RuntimeException} (surfaced as {@link TransformException}).</li>
 * </ul>
 */
public interface ExpressionEngine {

    /** Compiles the expression source; the returned handle is passed back to {@link #eval}. */
    Object compile(String expression);

    JsonNode eval(Object compiled, Context ctx);
}
