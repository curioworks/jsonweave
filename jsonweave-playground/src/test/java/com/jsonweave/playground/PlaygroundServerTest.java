package com.jsonweave.playground;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaygroundServerTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static Javalin app;
    private static HttpClient client;

    @BeforeAll
    static void start() {
        app = PlaygroundServer.start(0, true, true);
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        app.stop();
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/transform"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void transformsInputWithSpec() throws Exception {
        HttpResponse<String> res = post("""
                {"input": {"orders": [{"id": 1, "s": "A"}, {"id": 2, "s": "B"}]},
                 "spec": {"ids": {"#stream": "$.orders", "#filter": {"@.s": "A"}, "#map": "@.id"}}}
                """);
        assertEquals(200, res.statusCode());
        JsonNode body = M.readTree(res.body());
        assertEquals("[1]", body.get("output").get("ids").toString());
    }

    @Test
    void badSpecReturns400WithSpecPath() throws Exception {
        HttpResponse<String> res = post("""
                {"input": {}, "spec": {"x": {"#fliter": 1}}}
                """);
        assertEquals(400, res.statusCode());
        JsonNode body = M.readTree(res.body());
        assertTrue(body.get("error").asText().contains("#filter"));
        assertEquals("compile", body.get("phase").asText());
        assertEquals("$.x", body.get("specPath").asText());
    }

    @Test
    void examplesEndpointServesGallery() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/examples"))
                .GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        JsonNode examples = M.readTree(res.body());
        assertTrue(examples.isArray() && examples.size() >= 5);
        for (JsonNode ex : examples) {
            assertTrue(ex.has("name") && ex.has("input") && ex.has("spec"));
        }
    }

    @Test
    void everyExampleActuallyTransforms() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/examples"))
                .GET().build();
        JsonNode examples = M.readTree(client.send(req, HttpResponse.BodyHandlers.ofString()).body());
        for (JsonNode ex : examples) {
            var payload = M.createObjectNode();
            payload.set("input", ex.get("input"));
            payload.set("spec", ex.get("spec"));
            if (ex.has("expressions")) {
                payload.set("expressions", ex.get("expressions"));
            }
            HttpResponse<String> res = post(M.writeValueAsString(payload));
            assertEquals(200, res.statusCode(), () -> "example failed: " + ex.get("name") + " → " + res.body());
        }
    }

    @Test
    void expressionCatalogInBodyResolvesExprReferences() throws Exception {
        HttpResponse<String> res = post("""
                {"input": {"items": [{"v": 2}, {"v": 5}]},
                 "spec": {"out": {"#stream": "$.items", "#map": {"#expr": "double"}}},
                 "expressions": {"double": {"mvel": "it.v * 2", "js": "it.v * 2"}}}
                """);
        assertEquals(200, res.statusCode(), res.body());
        assertEquals("[4,10]", M.readTree(res.body()).get("output").get("out").toString());
    }

    @Test
    void inlineJsAndMvelWork() throws Exception {
        HttpResponse<String> res = post("""
                {"input": {"a": 3},
                 "spec": {"m": {"#mvel": "root.a * 2"}, "j": {"#js": "root.a * 3"}}}
                """);
        assertEquals(200, res.statusCode(), res.body());
        JsonNode out = M.readTree(res.body()).get("output");
        assertEquals(6, out.get("m").intValue());
        assertEquals(9, out.get("j").intValue());
    }
}
