package com.jsonweave;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.jsonweave.path.JsonPath;
import com.jsonweave.spec.Context;
import com.jsonweave.spec.Expr;
import com.jsonweave.spec.SpecCompiler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Entry point. A Jsonweave spec is shaped exactly like the desired <em>output</em>
 * document; each value declares where it comes from in the source and which
 * stream-style operations to apply.
 *
 * <pre>{@code
 * Transform t = Jsonweave.compile("""
 *     {
 *       "customerName": "$.user.firstName",
 *       "shippedOrderIds": { "#stream": "$.orders",
 *                            "#filter": {"status": "SHIPPED"},
 *                            "#map": "@.id" }
 *     }
 *     """);
 * JsonNode output = t.apply(input);
 * }</pre>
 *
 * Compile once, apply many times: compiled transforms are immutable and thread-safe.
 * Register custom functions through {@link #builder()}.
 */
public final class Jsonweave {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Jsonweave() {
    }

    /** Compiles a spec with no custom functions. Throws {@link SpecException} on invalid specs. */
    public static Transform compile(JsonNode spec) {
        return builder().compile(spec);
    }

    /** Compiles a spec given as JSON text. */
    public static Transform compile(String specJson) {
        return builder().compile(specJson);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<String, WeaveFunction> functions = new LinkedHashMap<>();
        private final Map<String, ExpressionEngine> engines = new LinkedHashMap<>();
        private final Map<String, JsonNode> expressionCatalog = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Registers a custom function, callable from specs as {@code #name}.
         * Names must be identifiers and must not collide with built-in operations.
         */
        public Builder register(String name, WeaveFunction function) {
            Objects.requireNonNull(function, "function");
            validateName(name, engines.keySet());
            functions.put(name, function);
            return this;
        }

        /**
         * Registers an expression dialect, callable from specs as {@code {"#name": "<code>"}}
         * and selectable by named catalog entries. <b>Registration order is the dialect
         * preference</b> when a catalog entry offers several — register the fastest first
         * (on the JVM: mvel before js).
         */
        public Builder registerExpressionEngine(String name, ExpressionEngine engine) {
            Objects.requireNonNull(engine, "engine");
            validateName(name, functions.keySet());
            engines.put(name, engine);
            return this;
        }

        /**
         * Adds a catalog of named expressions, referenced from specs as
         * {@code {"#expr": "entryName"}}. Each entry maps dialect names to code
         * ({@code {"discount": {"mvel": "...", "js": "..."}}}) or is a plain code string
         * for the default (first-registered) dialect. May be called repeatedly; later
         * catalogs override earlier entries of the same name.
         */
        public Builder expressions(JsonNode catalog) {
            Objects.requireNonNull(catalog, "catalog");
            if (!catalog.isObject()) {
                throw new IllegalArgumentException("an expression catalog must be a JSON object");
            }
            catalog.fields().forEachRemaining(e -> expressionCatalog.put(e.getKey(), e.getValue()));
            return this;
        }

        private void validateName(String name, java.util.Set<String> otherNamespace) {
            Objects.requireNonNull(name, "name");
            if (name.isEmpty() || !JsonPath.isIdentStart(name.charAt(0))
                    || !name.chars().allMatch(c -> JsonPath.isIdentPart((char) c))) {
                throw new IllegalArgumentException("name must be an identifier: '" + name + "'");
            }
            if (SpecCompiler.ALL_BUILTIN_OPS.contains("#" + name)) {
                throw new IllegalArgumentException("'#" + name + "' is a built-in operation");
            }
            if (otherNamespace.contains(name)) {
                throw new IllegalArgumentException("'#" + name + "' is already registered");
            }
        }

        public Transform compile(JsonNode spec) {
            Expr root = new SpecCompiler(Map.copyOf(functions), new LinkedHashMap<>(engines),
                    new LinkedHashMap<>(expressionCatalog)).compileRoot(spec);
            return new CompiledTransform(root);
        }

        public Transform compile(String specJson) {
            try {
                return compile(MAPPER.readTree(specJson));
            } catch (JsonProcessingException bad) {
                throw new SpecException("spec is not valid JSON: " + bad.getOriginalMessage(), "$");
            }
        }
    }

    private record CompiledTransform(Expr root) implements Transform {

        @Override
        public JsonNode apply(JsonNode input) {
            Objects.requireNonNull(input, "input");
            JsonNode out = root.eval(new Context(input));
            return out != null ? out : NullNode.getInstance();
        }

        @Override
        public String apply(String inputJson) {
            JsonNode input;
            try {
                input = MAPPER.readTree(inputJson);
            } catch (JsonProcessingException bad) {
                throw new TransformException("input is not valid JSON: " + bad.getOriginalMessage(), "$");
            }
            try {
                return MAPPER.writeValueAsString(apply(input));
            } catch (JsonProcessingException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
