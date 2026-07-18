package com.jsonweave;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonweave.ops.JsonCompare;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs every case in src/test/resources/fixtures/*.json. Each case is
 * {description, input, spec, expected} or {description, input, spec, error}.
 * The fixtures double as the language's executable specification.
 */
class FixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String[] FILES = {
            "structure", "pipelines", "matchers", "value-ops", "dynamic-keys", "composition", "errors"
    };

    @TestFactory
    Stream<DynamicNode> fixtures() {
        return Arrays.stream(FILES).map(file -> {
            JsonNode cases = load("/fixtures/" + file + ".json");
            List<DynamicTest> tests = new ArrayList<>();
            for (JsonNode c : cases) {
                tests.add(DynamicTest.dynamicTest(c.get("description").asText(), () -> runCase(c)));
            }
            return DynamicContainer.dynamicContainer(file, tests);
        });
    }

    private static JsonNode load(String resource) {
        try (InputStream in = FixtureTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing fixture resource " + resource);
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void runCase(JsonNode c) {
        JsonNode spec = c.get("spec");
        JsonNode input = c.get("input");
        JsonNode error = c.get("error");
        if (error != null) {
            String phase = error.path("phase").asText("compile");
            if (phase.equals("compile")) {
                SpecException ex = assertThrows(SpecException.class, () -> Jsonweave.compile(spec));
                checkError(error, ex.getMessage(), ex.specPath());
            } else {
                Transform t = Jsonweave.compile(spec);
                TransformException ex = assertThrows(TransformException.class, () -> t.apply(input));
                checkError(error, ex.getMessage(), ex.specPath());
            }
            return;
        }
        JsonNode expected = c.get("expected");
        assertNotNull(expected, "fixture case needs 'expected' or 'error'");
        JsonNode actual = Jsonweave.compile(spec).apply(input);
        assertTrue(JsonCompare.equalsNode(expected, actual),
                () -> "expected: " + expected + "\nactual:   " + actual);
    }

    private void checkError(JsonNode error, String message, String specPath) {
        if (error.has("contains")) {
            String needle = error.get("contains").asText();
            assertTrue(message.contains(needle),
                    () -> "expected message containing '" + needle + "' but was: " + message);
        }
        if (error.has("specPath")) {
            assertEquals(error.get("specPath").asText(), specPath);
        }
    }
}
