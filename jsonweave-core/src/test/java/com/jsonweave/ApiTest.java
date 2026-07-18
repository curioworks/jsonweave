package com.jsonweave;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void stringInStringOut() {
        Transform t = Jsonweave.compile("{\"x\": \"$.a\"}");
        assertEquals("{\"x\":1}", t.apply("{\"a\":1}"));
    }

    @Test
    void invalidSpecJsonIsASpecException() {
        assertThrows(SpecException.class, () -> Jsonweave.compile("{not json"));
    }

    @Test
    void invalidInputJsonIsATransformException() {
        Transform t = Jsonweave.compile("{\"x\": \"$.a\"}");
        assertThrows(TransformException.class, () -> t.apply("{not json"));
    }

    @Test
    void inputIsNeverMutated() throws Exception {
        JsonNode input = M.readTree("{\"user\": {\"n\": 1}, \"orders\": [{\"id\": 1}, {\"id\": 2}]}");
        JsonNode copy = input.deepCopy();
        Jsonweave.compile("""
                {"u": "$.user",
                 "ids": {"#stream": "$.orders", "#map": "@.id"},
                 "merged": {"#spread": "$.user", "extra": 1}}
                """).apply(input);
        assertEquals(copy, input);
    }

    @Test
    void compiledTransformIsReusableAndThreadSafe() throws Exception {
        Transform t = Jsonweave.compile("""
                {"total": {"#stream": "$.nums", "#filter": {"@": {"#gt": 0}}, "#sum": "@"}}
                """);
        JsonNode input = M.readTree("{\"nums\": [1, -2, 3, 4]}");
        List<JsonNode> results = IntStream.range(0, 200).parallel()
                .mapToObj(i -> t.apply(input))
                .toList();
        for (JsonNode r : results) {
            assertEquals(8, r.get("total").intValue());
        }
    }

    @Test
    void missingRootResultSerializesAsNull() {
        Transform t = Jsonweave.compile("\"$.nope\"");
        assertTrue(t.apply("{}").equals("null"));
    }
}
