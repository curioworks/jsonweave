package com.jsonweave.mvel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonweave.SpecException;
import com.jsonweave.Transform;
import com.jsonweave.TransformException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MvelEngineTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Transform compile(String spec) {
        return MvelEngine.builder().compile(json(spec));
    }

    @Test
    void arithmeticOnRootAndElement() {
        Transform t = compile("""
                {"gross": {"#mvel": "root.price * 1.18"},
                 "nets": {"#stream": "$.orders", "#map": {"#mvel": "it.total * 0.5"}}}
                """);
        JsonNode out = t.apply(json("{\"price\": 100, \"orders\": [{\"total\": 10}, {\"total\": 30}]}"));
        assertEquals(118.0, out.get("gross").asDouble(), 1e-9);
        assertEquals("[5.0,15.0]", out.get("nets").toString());
    }

    @Test
    void ternaryAndStringMethods() {
        Transform t = compile("""
                {"label": {"#mvel": "root.total > 100 ? 'big' : 'small'"},
                 "loud": {"#mvel": "root.name.toUpperCase()"}}
                """);
        JsonNode out = t.apply(json("{\"total\": 250, \"name\": \"jane\"}"));
        assertEquals("big", out.get("label").asText());
        assertEquals("JANE", out.get("loud").asText());
    }

    @Test
    void filterPredicate() {
        Transform t = compile("""
                {"kept": {"#stream": "$.orders",
                          "#filter": {"#mvel": "it.total > 100 && it.status != 'CANCELLED'"},
                          "#map": "@.id"}}
                """);
        JsonNode out = t.apply(json("""
                {"orders": [
                  {"id": 1, "total": 250, "status": "SHIPPED"},
                  {"id": 2, "total": 250, "status": "CANCELLED"},
                  {"id": 3, "total": 50, "status": "SHIPPED"}]}
                """));
        assertEquals("[1]", out.get("kept").toString());
    }

    @Test
    void deepAccessAndArrayIndexing() {
        Transform t = compile("""
                {"tier": {"#mvel": "root.customer.tier"},
                 "firstTag": {"#mvel": "root.tags[0]"},
                 "tagCount": {"#mvel": "root.tags.size()"}}
                """);
        JsonNode out = t.apply(json("{\"customer\": {\"tier\": \"GOLD\"}, \"tags\": [\"a\", \"b\"]}"));
        assertEquals("GOLD", out.get("tier").asText());
        assertEquals("a", out.get("firstTag").asText());
        assertEquals(2, out.get("tagCount").intValue());
    }

    @Test
    void letVariablesAreBoundByPlainName() {
        Transform t = compile("""
                {"#let": {"shipped": {"#stream": "$.orders", "#filter": {"@.s": "OK"}}},
                 "n": {"#mvel": "shipped.size()"}}
                """);
        assertEquals(2, t.apply(json("{\"orders\": [{\"s\": \"OK\"}, {\"s\": \"NO\"}, {\"s\": \"OK\"}]}"))
                .get("n").intValue());
    }

    @Test
    void expressionsReturningContainersBecomeJson() {
        Transform t = compile("{\"pair\": {\"#mvel\": \"[root.a, root.b]\"}}");
        assertEquals("[1,2]", t.apply(json("{\"a\": 1, \"b\": 2}")).get("pair").toString());
    }

    @Test
    void nullResultIsMissingAndDefaultComposes() {
        Transform t = compile("""
                {"gone": {"#mvel": "null"},
                 "or": {"#mvel": "null", "#default": 0}}
                """);
        JsonNode out = t.apply(json("{}"));
        assertFalse(out.has("gone"));
        assertEquals(0, out.get("or").intValue());
    }

    @Test
    void syntaxErrorIsASpecExceptionWithPath() {
        SpecException ex = assertThrows(SpecException.class,
                () -> compile("{\"x\": {\"#mvel\": \"root.total > ((\"}}"));
        assertEquals("$.x.#mvel", ex.specPath());
    }

    @Test
    void runtimeFailureIsATransformExceptionWithPath() {
        Transform t = compile("{\"x\": {\"#mvel\": \"root.nope.deeper\"}}");
        TransformException ex = assertThrows(TransformException.class, () -> t.apply(json("{}")));
        assertEquals("$.x.#mvel", ex.specPath());
    }

    @Test
    void nonBooleanFilterPredicateFails() {
        Transform t = compile("{\"x\": {\"#stream\": \"$.a\", \"#filter\": {\"#mvel\": \"it.n + 1\"}}}");
        TransformException ex = assertThrows(TransformException.class,
                () -> t.apply(json("{\"a\": [{\"n\": 1}]}")));
        assertTrue(ex.getMessage().contains("boolean"));
    }

    @Test
    void catalogEntriesResolveTheMvelDialect() {
        Transform t = MvelEngine.builder()
                .expressions(json("""
                        {"discount": {"mvel": "it.total > 100 ? it.total * 0.9 : it.total",
                                      "js": "it.total > 100 ? it.total * 0.9 : it.total"}}
                        """))
                .compile(json("""
                        {"nets": {"#stream": "$.orders", "#map": {"#expr": "discount"}}}
                        """));
        assertEquals("[180.0,50]",
                t.apply(json("{\"orders\": [{\"total\": 200}, {\"total\": 50}]}")).get("nets").toString());
    }
}
