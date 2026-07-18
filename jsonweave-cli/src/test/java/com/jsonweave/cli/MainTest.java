package com.jsonweave.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @TempDir
    Path dir;

    private int run(String... args) {
        return new CommandLine(new Main()).execute(args);
    }

    @Test
    void transformsFileToFile() throws Exception {
        Path spec = Files.writeString(dir.resolve("spec.json"),
                "{\"ids\": {\"#stream\": \"$.items\", \"#map\": \"@.id\"}}");
        Path input = Files.writeString(dir.resolve("in.json"),
                "{\"items\": [{\"id\": 1}, {\"id\": 2}]}");
        Path out = dir.resolve("out.json");

        int code = run("-s", spec.toString(), input.toString(), "-o", out.toString(), "--compact");

        assertEquals(0, code);
        assertEquals("{\"ids\":[1,2]}", Files.readString(out).trim());
    }

    @Test
    void invalidSpecExitsWithCode2() throws Exception {
        Path spec = Files.writeString(dir.resolve("spec.json"), "{\"x\": {\"#nope\": 1}}");
        Path input = Files.writeString(dir.resolve("in.json"), "{}");
        assertEquals(2, run("-s", spec.toString(), input.toString()));
    }

    @Test
    void missingSpecFileExitsWithCode1() throws Exception {
        Path input = Files.writeString(dir.resolve("in.json"), "{}");
        assertEquals(1, run("-s", dir.resolve("ghost.json").toString(), input.toString()));
    }

    @Test
    void runtimeFailureExitsWithCode3() throws Exception {
        Path spec = Files.writeString(dir.resolve("spec.json"),
                "{\"x\": {\"#stream\": \"$.items\", \"#toObject\": {\"#key\": \"@\", \"#value\": \"@\"}}}");
        Path input = Files.writeString(dir.resolve("in.json"), "{\"items\": [[1, 2]]}");
        assertEquals(3, run("-s", spec.toString(), input.toString()));
    }

    @Test
    void mvelExpressionsWorkByDefaultAndDieWithNoMvel() throws Exception {
        Path spec = Files.writeString(dir.resolve("spec.json"),
                "{\"n\": {\"#mvel\": \"root.a * 2\"}}");
        Path input = Files.writeString(dir.resolve("in.json"), "{\"a\": 21}");
        Path out = dir.resolve("out.json");

        assertEquals(0, run("-s", spec.toString(), input.toString(), "-o", out.toString(), "--compact"));
        assertEquals("{\"n\":42}", Files.readString(out).trim());
        assertEquals(2, run("-s", spec.toString(), input.toString(), "--no-mvel", "--no-js"));
    }

    @Test
    void expressionCatalogResolvesExprReferences() throws Exception {
        Path catalog = Files.writeString(dir.resolve("expressions.json"),
                "{\"double\": {\"mvel\": \"it.v * 2\", \"js\": \"it.v * 2\"}}");
        Path spec = Files.writeString(dir.resolve("spec.json"),
                "{\"out\": {\"#stream\": \"$.items\", \"#map\": {\"#expr\": \"double\"}}}");
        Path input = Files.writeString(dir.resolve("in.json"), "{\"items\": [{\"v\": 1}, {\"v\": 3}]}");
        Path out = dir.resolve("out.json");

        assertEquals(0, run("-s", spec.toString(), input.toString(),
                "-e", catalog.toString(), "-o", out.toString(), "--compact"));
        assertEquals("{\"out\":[2,6]}", Files.readString(out).trim());
    }

    @Test
    void prettyPrintsByDefault() throws Exception {
        Path spec = Files.writeString(dir.resolve("spec.json"), "{\"a\": \"$.a\"}");
        Path input = Files.writeString(dir.resolve("in.json"), "{\"a\": {\"b\": 1}}");
        Path out = dir.resolve("out.json");
        assertEquals(0, run("-s", spec.toString(), input.toString(), "-o", out.toString()));
        assertTrue(Files.readString(out).contains(System.lineSeparator()));
    }
}
