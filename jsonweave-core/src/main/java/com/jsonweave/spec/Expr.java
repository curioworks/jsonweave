package com.jsonweave.spec;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A compiled spec expression. Evaluation returns {@code null} to mean <em>missing</em>
 * (distinct from JSON {@code null}, which is a {@code NullNode}); missing values cause
 * their output key/element to be omitted.
 */
@FunctionalInterface
public interface Expr {

    JsonNode eval(Context ctx);
}
