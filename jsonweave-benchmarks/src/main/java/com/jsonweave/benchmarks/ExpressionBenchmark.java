package com.jsonweave.benchmarks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsonweave.Jsonweave;
import com.jsonweave.Transform;
import com.jsonweave.js.JsEngine;
import com.jsonweave.mvel.MvelEngine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * The performance ladder for custom logic: the same filter+map over 1000 orders expressed
 * with built-in operations, #mvel, and #js. Expected ordering: builtins &lt; mvel &lt; js.
 *
 * Run: java -jar jsonweave-benchmarks/target/benchmarks.jar ExpressionBenchmark
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ExpressionBenchmark {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BUILTIN_SPEC = """
            {"nets": {"#stream": "$.orders",
                      "#filter": {"@.total": {"#gt": 100}},
                      "#map": "@.total"}}
            """;

    private static final String MVEL_SPEC = """
            {"nets": {"#stream": "$.orders",
                      "#filter": {"#mvel": "it.total > 100"},
                      "#map": {"#mvel": "it.total * 0.9"}}}
            """;

    private static final String JS_SPEC = """
            {"nets": {"#stream": "$.orders",
                      "#filter": {"#js": "it.total > 100"},
                      "#map": {"#js": "it.total * 0.9"}}}
            """;

    private Transform builtin;
    private Transform mvel;
    private Transform js;
    private JsonNode input;

    @Setup
    public void setup() throws Exception {
        builtin = Jsonweave.compile(BUILTIN_SPEC);
        mvel = MvelEngine.builder().compile(MAPPER.readTree(MVEL_SPEC));
        js = JsEngine.builder().compile(MAPPER.readTree(JS_SPEC));

        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode orders = root.putArray("orders");
        for (int i = 0; i < 1000; i++) {
            orders.addObject().put("id", i).put("total", (i * 7919) % 1000);
        }
        input = root;
    }

    @Benchmark
    public Object builtins() {
        return builtin.apply(input);
    }

    @Benchmark
    public Object mvelExpressions() {
        return mvel.apply(input);
    }

    @Benchmark
    public Object jsExpressions() {
        return js.apply(input);
    }
}
