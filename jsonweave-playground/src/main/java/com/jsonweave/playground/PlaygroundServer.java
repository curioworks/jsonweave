package com.jsonweave.playground;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsonweave.Jsonweave;
import com.jsonweave.SpecException;
import com.jsonweave.TransformException;
import com.jsonweave.js.JsEngine;
import com.jsonweave.mvel.MvelEngine;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

/**
 * The Jsonweave playground: input, spec and optional expression-catalog panes in the
 * browser, transformation executed here on the JVM by jsonweave-core.
 *
 * <pre>
 *   POST /api/transform   {"input": ..., "spec": ..., "expressions": {...}?}
 *        → 200 {"output": ...}
 *        → 400 {"error": "...", "specPath": "$....", "phase": "compile"|"apply"}
 *   GET  /api/examples    curated example gallery
 * </pre>
 *
 * <p><b>Security:</b> {@code #mvel} executes arbitrary Java — never host this publicly
 * with MVEL enabled. Disable engines via {@code JSONWEAVE_MVEL=false} /
 * {@code JSONWEAVE_JS=false} ({@code #js} is sandboxed, but stays disableable).
 */
public final class PlaygroundServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MvelEngine MVEL = new MvelEngine();
    private static final JsEngine JS = new JsEngine();

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7070"));
        boolean mvel = envEnabled("JSONWEAVE_MVEL");
        boolean js = envEnabled("JSONWEAVE_JS");
        start(port, mvel, js);
        System.out.println("Jsonweave playground running at http://localhost:" + port
                + "  (mvel: " + (mvel ? "on" : "off") + ", js: " + (js ? "on" : "off") + ")");
    }

    private static boolean envEnabled(String name) {
        return !"false".equalsIgnoreCase(System.getenv().getOrDefault(name, "true"));
    }

    static Javalin start(int port, boolean mvelEnabled, boolean jsEnabled) {
        return Javalin.create(config -> config.staticFiles.add("/public", Location.CLASSPATH))
                .post("/api/transform", ctx -> {
                    JsonNode body = MAPPER.readTree(ctx.bodyInputStream());
                    JsonNode spec = body.get("spec");
                    JsonNode input = body.get("input");
                    ObjectNode response = MAPPER.createObjectNode();
                    if (spec == null || input == null) {
                        ctx.status(400).json(response.put("error", "body must be {\"input\": ..., \"spec\": ...}"));
                        return;
                    }
                    try {
                        Jsonweave.Builder builder = Jsonweave.builder();
                        if (mvelEnabled) {
                            builder.registerExpressionEngine("mvel", MVEL); // registered first: preferred dialect
                        }
                        if (jsEnabled) {
                            builder.registerExpressionEngine("js", JS);
                        }
                        JsonNode expressions = body.get("expressions");
                        if (expressions != null && expressions.isObject() && expressions.size() > 0) {
                            builder.expressions(expressions);
                        }
                        response.set("output", builder.compile(spec).apply(input));
                        ctx.json(response);
                    } catch (SpecException bad) {
                        response.put("error", bad.getMessage())
                                .put("specPath", bad.specPath())
                                .put("phase", "compile");
                        ctx.status(400).json(response);
                    } catch (TransformException failed) {
                        response.put("error", failed.getMessage())
                                .put("specPath", failed.specPath())
                                .put("phase", "apply");
                        ctx.status(400).json(response);
                    }
                })
                .get("/api/examples", ctx -> {
                    ctx.contentType("application/json");
                    ctx.result(PlaygroundServer.class.getResourceAsStream("/examples.json"));
                })
                .start(port);
    }

    private PlaygroundServer() {
    }
}
