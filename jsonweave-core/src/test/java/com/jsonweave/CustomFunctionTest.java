package com.jsonweave;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomFunctionTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void standaloneFunctionReceivesResolvedArgAsList() {
        Transform t = Jsonweave.builder()
                .register("countOf", (input, arg) -> IntNode.valueOf(input.size()))
                .compile(json("{\"n\": {\"#countOf\": \"$.items\"}, \"one\": {\"#countOf\": \"$.scalar\"}}"));
        JsonNode out = t.apply(json("{\"items\": [1, 2, 3], \"scalar\": \"x\"}"));
        assertEquals(3, out.get("n").intValue());
        assertEquals(1, out.get("one").intValue());
    }

    @Test
    void standaloneFunctionWithMissingArgGetsEmptyList() {
        Transform t = Jsonweave.builder()
                .register("countOf", (input, arg) -> IntNode.valueOf(input.size()))
                .compile(json("{\"n\": {\"#countOf\": \"$.nope\"}}"));
        assertEquals(0, t.apply(json("{}")).get("n").intValue());
    }

    @Test
    void pipelineStageReturningArrayContinuesThePipeline() {
        Transform t = Jsonweave.builder()
                .register("doubleAll", (input, arg) -> {
                    ArrayNode out = JsonNodeFactory.instance.arrayNode();
                    input.forEach(n -> out.add(n.intValue() * 2));
                    return out;
                })
                .compile(json("{\"x\": {\"#stream\": \"$.nums\", \"#doubleAll\": true, \"#sum\": \"@\"}}"));
        assertEquals(12, t.apply(json("{\"nums\": [1, 2, 3]}")).get("x").intValue());
    }

    @Test
    void pipelineStageReturningScalarEndsThePipeline() {
        Transform t = Jsonweave.builder()
                .register("pickFirst", (input, arg) -> input.isEmpty() ? null : input.get(0))
                .compile(json("{\"x\": {\"#stream\": \"$.nums\", \"#pickFirst\": true}}"));
        assertEquals(7, t.apply(json("{\"nums\": [7, 8]}")).get("x").intValue());
    }

    @Test
    void scalarResultBeforeMoreStagesFailsAtApplyTime() {
        Transform t = Jsonweave.builder()
                .register("collapse", (input, arg) -> IntNode.valueOf(input.size()))
                .compile(json("{\"x\": {\"#stream\": \"$.nums\", \"#collapse\": true, \"#count\": \"@\"}}"));
        TransformException ex = assertThrows(TransformException.class, () -> t.apply(json("{\"nums\": [1]}")));
        assertTrue(ex.getMessage().contains("non-array"));
    }

    @Test
    void functionReturningNullMeansMissing() {
        Transform t = Jsonweave.builder()
                .register("never", (input, arg) -> null)
                .compile(json("{\"x\": {\"#never\": \"$.a\"}, \"y\": 1}"));
        JsonNode out = t.apply(json("{\"a\": 1}"));
        assertFalse(out.has("x"));
        assertEquals(1, out.get("y").intValue());
    }

    @Test
    void functionReceivesRawSpecArg() {
        Transform t = Jsonweave.builder()
                .register("argEcho", (input, arg) -> arg)
                .compile(json("{\"x\": {\"#stream\": \"$.nums\", \"#argEcho\": {\"opt\": 42}}}"));
        assertEquals(42, t.apply(json("{\"nums\": []}")).get("x").get("opt").intValue());
    }

    @Test
    void unregisteredFunctionIsACompileError() {
        SpecException ex = assertThrows(SpecException.class,
                () -> Jsonweave.compile(json("{\"x\": {\"#mystery\": \"$.a\"}}")));
        assertTrue(ex.getMessage().contains("unknown operation"));
    }

    @Test
    void throwingFunctionIsWrappedWithItsSpecPath() {
        Transform t = Jsonweave.builder()
                .register("boom", (input, arg) -> {
                    throw new IllegalStateException("kaboom");
                })
                .compile(json("{\"x\": {\"#boom\": \"$.a\"}}"));
        TransformException ex = assertThrows(TransformException.class, () -> t.apply(json("{\"a\": 1}")));
        assertTrue(ex.getMessage().contains("kaboom"));
        assertEquals("$.x.#boom", ex.specPath());
    }

    @Test
    void builtinNamesAndBadIdentifiersAreRejectedAtRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> Jsonweave.builder().register("filter", (in, a) -> null));
        assertThrows(IllegalArgumentException.class,
                () -> Jsonweave.builder().register("bad name", (in, a) -> null));
        assertThrows(IllegalArgumentException.class,
                () -> Jsonweave.builder().register("", (in, a) -> null));
    }
}
