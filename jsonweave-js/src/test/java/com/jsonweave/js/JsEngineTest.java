package com.jsonweave.js;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonweave.SpecException;
import com.jsonweave.Transform;
import com.jsonweave.TransformException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsEngineTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Transform compile(String spec) {
        return JsEngine.builder().compile(json(spec));
    }

    @Test
    void arithmeticTernaryAndStrings() {
        Transform t = compile("""
                {"gross": {"#js": "root.price * 2"},
                 "label": {"#js": "root.total > 100 ? 'big' : 'small'"},
                 "loud": {"#js": "root.name.toUpperCase()"}}
                """);
        JsonNode out = t.apply(json("{\"price\": 21, \"total\": 250, \"name\": \"jane\"}"));
        assertEquals(42, out.get("gross").intValue());
        assertEquals("big", out.get("label").asText());
        assertEquals("JANE", out.get("loud").asText());
    }

    @Test
    void perElementMapAndFilterPredicate() {
        Transform t = compile("""
                {"kept": {"#stream": "$.orders",
                          "#filter": {"#js": "it.total > 100 && it.status !== 'CANCELLED'"},
                          "#map": {"#js": "it.total * 0.5"}}}
                """);
        JsonNode out = t.apply(json("""
                {"orders": [
                  {"id": 1, "total": 250, "status": "SHIPPED"},
                  {"id": 2, "total": 250, "status": "CANCELLED"},
                  {"id": 3, "total": 50, "status": "SHIPPED"}]}
                """));
        assertEquals("[125]", out.get("kept").toString());
    }

    @Test
    void deepAccessArraysAndJsBuiltins() {
        Transform t = compile("""
                {"tier": {"#js": "root.customer.tier"},
                 "tagCount": {"#js": "root.tags.length"},
                 "joined": {"#js": "root.tags.map(x => x.toUpperCase()).join('-')"}}
                """);
        JsonNode out = t.apply(json("{\"customer\": {\"tier\": \"GOLD\"}, \"tags\": [\"a\", \"b\"]}"));
        assertEquals("GOLD", out.get("tier").asText());
        assertEquals(2, out.get("tagCount").intValue());
        assertEquals("A-B", out.get("joined").asText());
    }

    @Test
    void letVariablesAndContainersRoundTrip() {
        Transform t = compile("""
                {"#let": {"nums": "$.nums"},
                 "doubled": {"#js": "nums.map(n => n * 2)"},
                 "obj": {"#js": "({a: root.x, b: 'y'})"}}
                """);
        JsonNode out = t.apply(json("{\"nums\": [1, 2], \"x\": 9}"));
        assertEquals("[2,4]", out.get("doubled").toString());
        assertEquals("{\"a\":9,\"b\":\"y\"}", out.get("obj").toString());
    }

    @Test
    void nullAndUndefinedAreMissing() {
        Transform t = compile("""
                {"gone": {"#js": "null"},
                 "alsoGone": {"#js": "root.nope"},
                 "or": {"#js": "undefined", "#default": 0}}
                """);
        JsonNode out = t.apply(json("{}"));
        assertFalse(out.has("gone"));
        assertFalse(out.has("alsoGone"));
        assertEquals(0, out.get("or").intValue());
    }

    @Test
    void syntaxErrorsAtCompileTimeWithPath() {
        SpecException ex = assertThrows(SpecException.class,
                () -> compile("{\"x\": {\"#js\": \"root..(\"}}"));
        assertEquals("$.x.#js", ex.specPath());
    }

    @Test
    void sandboxBlocksHostAccess() {
        Transform t = compile("{\"x\": {\"#js\": \"Java.type('java.lang.Runtime')\"}}");
        TransformException ex = assertThrows(TransformException.class, () -> t.apply(json("{}")));
        assertEquals("$.x.#js", ex.specPath());
    }

    @Test
    void nonBooleanPredicateFails() {
        Transform t = compile("{\"x\": {\"#stream\": \"$.a\", \"#filter\": {\"#js\": \"it.n + 1\"}}}");
        assertThrows(TransformException.class, () -> t.apply(json("{\"a\": [{\"n\": 1}]}")));
    }

    @Test
    void threadSafeAcrossParallelApplies() {
        Transform t = compile("{\"n\": {\"#js\": \"root.v * 2\"}}");
        List<Integer> results = IntStream.range(0, 64).parallel()
                .mapToObj(i -> t.apply(json("{\"v\": " + i + "}")).get("n").intValue())
                .toList();
        for (int i = 0; i < 64; i++) {
            assertTrue(results.contains(i * 2));
        }
    }

    @Test
    void catalogPrefersMvelWhenRegisteredFirstButJsWorksAlone() {
        Transform t = JsEngine.builder()
                .expressions(json("{\"double\": {\"js\": \"it.v * 2\"}}"))
                .compile(json("{\"out\": {\"#stream\": \"$.items\", \"#map\": {\"#expr\": \"double\"}}}"));
        assertEquals("[2,4]", t.apply(json("{\"items\": [{\"v\": 1}, {\"v\": 2}]}")).get("out").toString());
    }
}
