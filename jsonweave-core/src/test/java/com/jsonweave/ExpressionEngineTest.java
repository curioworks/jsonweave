package com.jsonweave;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jsonweave.spec.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the pluggable {@link ExpressionEngine} API and named expression catalogs
 * with stub engines, keeping jsonweave-core free of any real engine dependency.
 */
class ExpressionEngineTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Stub dialect: "upper:FIELD" uppercases it.FIELD; "gt:FIELD:N" is a boolean predicate. */
    private static final class StubEngine implements ExpressionEngine {
        final String tag;

        StubEngine(String tag) {
            this.tag = tag;
        }

        @Override
        public Object compile(String expression) {
            if (!expression.startsWith("upper:") && !expression.startsWith("gt:")) {
                throw new IllegalArgumentException("bad stub syntax: " + expression);
            }
            return expression;
        }

        @Override
        public JsonNode eval(Object compiled, Context ctx) {
            String[] parts = ((String) compiled).split(":");
            JsonNode field = ctx.currentOrRoot().get(parts[1]);
            if (parts[0].equals("gt")) {
                return BooleanNode.valueOf(field != null && field.asDouble() > Double.parseDouble(parts[2]));
            }
            if (field == null) {
                return null;
            }
            if (field.isNull()) {
                throw new IllegalStateException("null field");
            }
            return TextNode.valueOf(tag + ":" + field.asText().toUpperCase());
        }
    }

    private Jsonweave.Builder builder() {
        return Jsonweave.builder()
                .registerExpressionEngine("fast", new StubEngine("fast"))
                .registerExpressionEngine("portable", new StubEngine("portable"));
    }

    @Test
    void engineOpInValuePositionAndInsideMap() {
        Transform t = builder().compile(json("""
                {"shout": {"#fast": "upper:name"},
                 "all": {"#stream": "$.users", "#map": {"#fast": "upper:name"}}}
                """));
        JsonNode out = t.apply(json("{\"name\": \"jane\", \"users\": [{\"name\": \"raj\"}]}"));
        assertEquals("fast:JANE", out.get("shout").asText());
        assertEquals("fast:RAJ", out.get("all").get(0).asText());
    }

    @Test
    void engineOpAsFilterPredicate() {
        Transform t = builder().compile(json("""
                {"big": {"#stream": "$.nums2", "#filter": {"#fast": "gt:v:5"}, "#map": "@.v"}}
                """));
        assertEquals("[9]", t.apply(json("{\"nums2\": [{\"v\": 1}, {\"v\": 9}]}")).get("big").toString());
    }

    @Test
    void nonBooleanPredicateFailsAtApplyTime() {
        Transform t = builder().compile(json("""
                {"x": {"#stream": "$.items", "#filter": {"#fast": "upper:name"}}}
                """));
        TransformException ex = assertThrows(TransformException.class,
                () -> t.apply(json("{\"items\": [{\"name\": \"a\"}]}")));
        assertTrue(ex.getMessage().contains("boolean"));
    }

    @Test
    void syntaxErrorsSurfaceAtCompileWithSpecPath() {
        SpecException ex = assertThrows(SpecException.class,
                () -> builder().compile(json("{\"x\": {\"#fast\": \"nonsense\"}}")));
        assertEquals("$.x.#fast", ex.specPath());
        assertTrue(ex.getMessage().contains("syntax"));
    }

    @Test
    void runtimeErrorsSurfaceAsTransformExceptionWithSpecPath() {
        Transform t = builder().compile(json("{\"x\": {\"#fast\": \"upper:name\"}}"));
        TransformException ex = assertThrows(TransformException.class,
                () -> t.apply(json("{\"name\": null}")));
        assertEquals("$.x.#fast", ex.specPath());
    }

    @Test
    void missingResultOmitsKeyAndDefaultComposes() {
        Transform t = builder().compile(json("""
                {"gone": {"#fast": "upper:nope"},
                 "or": {"#fast": "upper:nope", "#default": "fallback"}}
                """));
        JsonNode out = t.apply(json("{}"));
        assertFalse(out.has("gone"));
        assertEquals("fallback", out.get("or").asText());
    }

    @Test
    void engineOpIsNotAPipelineStage() {
        SpecException ex = assertThrows(SpecException.class,
                () -> builder().compile(json("{\"x\": {\"#stream\": \"$.a\", \"#fast\": \"upper:name\"}}")));
        assertTrue(ex.getMessage().contains("#map"));
    }

    @Test
    void catalogPicksDialectByRegistrationOrder() {
        Transform t = builder()
                .expressions(json("""
                        {"shout": {"portable": "upper:name", "fast": "upper:name"},
                         "portableOnly": {"portable": "upper:name"}}
                        """))
                .compile(json("{\"a\": {\"#expr\": \"shout\"}, \"b\": {\"#expr\": \"portableOnly\"}}"));
        JsonNode out = t.apply(json("{\"name\": \"jo\"}"));
        assertEquals("fast:JO", out.get("a").asText());      // fast registered first → preferred
        assertEquals("portable:JO", out.get("b").asText());  // falls back to what the entry offers
    }

    @Test
    void plainStringEntryUsesDefaultDialect() {
        Transform t = builder()
                .expressions(json("{\"shout\": \"upper:name\"}"))
                .compile(json("{\"a\": {\"#expr\": \"shout\"}}"));
        assertEquals("fast:JO", t.apply(json("{\"name\": \"jo\"}")).get("a").asText());
    }

    @Test
    void specLocalExpressionsBlockShadowsExternalCatalog() {
        Transform t = builder()
                .expressions(json("{\"shout\": {\"fast\": \"upper:name\"}}"))
                .compile(json("""
                        {"#expressions": {"shout": {"fast": "upper:other"}},
                         "a": {"#expr": "shout"}}
                        """));
        assertEquals("fast:LOCAL",
                t.apply(json("{\"name\": \"ext\", \"other\": \"local\"}")).get("a").asText());
    }

    @Test
    void expressionsBlockWorksBesideChainAndIsInheritedBySteps() {
        Transform t = builder().compile(json("""
                {"#expressions": {"shout": {"fast": "upper:name"}},
                 "#chain": [{"name": "$.name", "loud": {"#expr": "shout"}},
                            {"final": "$.loud"}]}
                """));
        assertEquals("fast:JO", t.apply(json("{\"name\": \"jo\"}")).get("final").asText());
    }

    @Test
    void unknownExpressionNameGetsSuggestion() {
        SpecException ex = assertThrows(SpecException.class, () -> builder()
                .expressions(json("{\"discount\": \"upper:name\"}"))
                .compile(json("{\"a\": {\"#expr\": \"discunt\"}}")));
        assertTrue(ex.getMessage().contains("discount"));
    }

    @Test
    void entryWithNoMatchingDialectFailsAtCompile() {
        SpecException ex = assertThrows(SpecException.class, () -> builder()
                .expressions(json("{\"shout\": {\"lua\": \"whatever\"}}"))
                .compile(json("{\"a\": {\"#expr\": \"shout\"}}")));
        assertTrue(ex.getMessage().contains("lua"));
        assertTrue(ex.getMessage().contains("fast"));
    }

    @Test
    void exprWithoutAnyEngineFailsAtCompile() {
        SpecException ex = assertThrows(SpecException.class, () -> Jsonweave.builder()
                .expressions(json("{\"shout\": \"upper:name\"}"))
                .compile(json("{\"a\": {\"#expr\": \"shout\"}}")));
        assertTrue(ex.getMessage().contains("engine"));
    }

    @Test
    void expressionsBlockOnlyAtRoot() {
        SpecException ex = assertThrows(SpecException.class, () -> builder()
                .compile(json("{\"x\": {\"#expressions\": {\"a\": \"upper:n\"}, \"#get\": \"$.a\"}}")));
        assertTrue(ex.getMessage().contains("root"));
    }

    @Test
    void engineNamesValidatedAndCollisionsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Jsonweave.builder().registerExpressionEngine("filter", new StubEngine("x")));
        assertThrows(IllegalArgumentException.class,
                () -> Jsonweave.builder().registerExpressionEngine("expr", new StubEngine("x")));
        assertThrows(IllegalArgumentException.class, () -> Jsonweave.builder()
                .register("dup", (in, a) -> null)
                .registerExpressionEngine("dup", new StubEngine("x")));
    }

    @Test
    void typoOnEngineNameGetsSuggestion() {
        SpecException ex = assertThrows(SpecException.class,
                () -> builder().compile(json("{\"x\": {\"#fsat\": \"upper:name\"}}")));
        assertTrue(ex.getMessage().contains("#fast"));
    }
}
