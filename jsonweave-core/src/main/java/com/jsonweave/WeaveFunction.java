package com.jsonweave;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A user-supplied function, registered via {@link Jsonweave#builder()}, callable from
 * specs as {@code #name}.
 *
 * <p>Invocation forms:
 * <ul>
 *   <li><b>Pipeline stage</b> — {@code {"#stream": "$.orders", "#myFn": <arg>}}: {@code input}
 *       is the current stream contents. If the function returns an array, the pipeline
 *       continues with its elements; any other result ends the pipeline with that value.</li>
 *   <li><b>Standalone</b> — {@code {"#myFn": "$.orders"}}: the argument is resolved and
 *       supplies {@code input} (array → its elements, scalar/object → singleton list,
 *       missing → empty list).</li>
 * </ul>
 */
@FunctionalInterface
public interface WeaveFunction {

    /**
     * @param input the JSON nodes flowing into the function (never null, possibly empty)
     * @param arg   the raw, unevaluated spec value of the {@code #name} key
     * @return the resulting JSON node; {@code null} means "missing" (the output key is omitted)
     */
    JsonNode apply(List<JsonNode> input, JsonNode arg);
}
