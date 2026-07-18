package com.jsonweave;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A compiled, immutable, thread-safe Jsonweave transformation.
 * Compile once with {@link Jsonweave#compile}, then apply to any number of documents.
 */
public interface Transform {

    /**
     * Applies the transformation to {@code input} and returns the output document.
     * The input is never mutated. The returned tree may share unmodified subtrees
     * with the input, so treat both as immutable.
     */
    JsonNode apply(JsonNode input);

    /** Convenience: parses {@code inputJson}, applies the transform, returns serialized output. */
    String apply(String inputJson);
}
