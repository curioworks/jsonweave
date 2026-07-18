package com.jsonweave.spec;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Evaluation context: the source-document root ({@code $.}), the current element
 * ({@code @.}, set inside per-element scopes like {@code #map}), and the {@code #let}
 * variables in scope.
 */
public final class Context {

    private final JsonNode root;
    private final JsonNode current;
    private final Map<String, JsonNode> vars;

    public Context(JsonNode root) {
        this(root, null, Map.of());
    }

    private Context(JsonNode root, JsonNode current, Map<String, JsonNode> vars) {
        this.root = root;
        this.current = current;
        this.vars = vars;
    }

    public JsonNode root() {
        return root;
    }

    /** The current element, or {@code null} when not inside a per-element scope. */
    public JsonNode current() {
        return current;
    }

    /** Element-relative references fall back to the root outside element scopes. */
    public JsonNode currentOrRoot() {
        return current != null ? current : root;
    }

    /** Value of a {@code #let} variable, or {@code null} if it evaluated to missing. */
    public JsonNode var(String name) {
        return vars.get(name);
    }

    /** All {@code #let} variables in scope, unmodifiable — for expression-engine bindings. */
    public Map<String, JsonNode> vars() {
        return java.util.Collections.unmodifiableMap(vars);
    }

    public Context withCurrent(JsonNode element) {
        return new Context(root, element, vars);
    }

    public Context withVars(Map<String, JsonNode> newVars) {
        return new Context(root, current, newVars);
    }
}
