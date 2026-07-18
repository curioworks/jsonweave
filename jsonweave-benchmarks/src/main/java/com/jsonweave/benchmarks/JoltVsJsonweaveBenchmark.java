package com.jsonweave.benchmarks;

import com.bazaarvoice.jolt.Chainr;
import com.bazaarvoice.jolt.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsonweave.Jsonweave;
import com.jsonweave.Transform;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Head-to-head: Jolt shift vs Jsonweave on equivalent transforms, plus a
 * filter/sort/aggregate pipeline (which Jolt cannot express) against a
 * hand-written Jackson baseline. Each engine receives its natural document
 * representation (Jolt: Maps/Lists, Jsonweave: JsonNode), both prepared in setup.
 *
 * Run: java -jar jsonweave-benchmarks/target/benchmarks.jar
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class JoltVsJsonweaveBenchmark {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FLAT_INPUT = """
            {"user": {"first": "Jane", "last": "Doe", "email": "jane@example.com"},
             "account": {"id": 42, "tier": "GOLD"}}
            """;

    private static final String FLAT_JOLT_SPEC = """
            [{"operation": "shift", "spec": {
                "user": {"first": "firstName", "last": "lastName", "email": "contact.email"},
                "account": {"id": "accountId", "tier": "tier"}}}]
            """;

    private static final String FLAT_WEAVE_SPEC = """
            {"firstName": "$.user.first",
             "lastName": "$.user.last",
             "contact": {"email": "$.user.email"},
             "accountId": "$.account.id",
             "tier": "$.account.tier"}
            """;

    private static final String NESTED_JOLT_SPEC = """
            [{"operation": "shift", "spec": {
                "orders": {"*": {
                    "id": "orders[&1].orderId",
                    "total": "orders[&1].amount",
                    "customer": {"name": "orders[&2].buyer"}}}}}]
            """;

    private static final String NESTED_WEAVE_SPEC = """
            {"orders": {"#stream": "$.orders",
                        "#map": {"orderId": "@.id", "amount": "@.total", "buyer": "@.customer.name"}}}
            """;

    private static final String PIPELINE_WEAVE_SPEC = """
            {"topIds": {"#stream": "$.orders",
                        "#filter": {"@.status": "SHIPPED", "@.total": {"#gt": 100}},
                        "#sortBy": {"#path": "@.total", "#order": "desc"},
                        "#limit": 10,
                        "#map": "@.id"},
             "revenue": {"#stream": "$.orders", "#filter": {"@.status": "SHIPPED"}, "#sum": "@.total"}}
            """;

    private Chainr flatJolt;
    private Transform flatWeave;
    private Object flatInputJolt;
    private JsonNode flatInputWeave;

    private Chainr nestedJolt;
    private Transform nestedWeave;
    private Object nestedInputJolt;
    private JsonNode nestedInputWeave;

    private Transform pipelineWeave;
    private JsonNode largeInputWeave;

    @Setup
    public void setup() throws Exception {
        flatJolt = Chainr.fromSpec(JsonUtils.jsonToList(FLAT_JOLT_SPEC));
        flatWeave = Jsonweave.compile(FLAT_WEAVE_SPEC);
        flatInputJolt = JsonUtils.jsonToObject(FLAT_INPUT);
        flatInputWeave = MAPPER.readTree(FLAT_INPUT);

        String nestedInput = ordersJson(100);
        nestedJolt = Chainr.fromSpec(JsonUtils.jsonToList(NESTED_JOLT_SPEC));
        nestedWeave = Jsonweave.compile(NESTED_WEAVE_SPEC);
        nestedInputJolt = JsonUtils.jsonToObject(nestedInput);
        nestedInputWeave = MAPPER.readTree(nestedInput);

        pipelineWeave = Jsonweave.compile(PIPELINE_WEAVE_SPEC);
        largeInputWeave = MAPPER.readTree(ordersJson(1000));
    }

    private static String ordersJson(int n) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode orders = root.putArray("orders");
        String[] statuses = {"SHIPPED", "CANCELLED", "PENDING"};
        String[] tiers = {"GOLD", "SILVER", "BRONZE"};
        for (int i = 0; i < n; i++) {
            ObjectNode o = orders.addObject();
            o.put("id", i);
            o.put("total", (i * 7919) % 1000);
            o.put("status", statuses[i % statuses.length]);
            o.putObject("customer")
                    .put("name", "customer-" + i)
                    .put("tier", tiers[i % tiers.length]);
        }
        return MAPPER.writeValueAsString(root);
    }

    // ------------------------------------------------------------ flat remapping

    @Benchmark
    public Object flatShift_jolt() {
        return flatJolt.transform(flatInputJolt);
    }

    @Benchmark
    public Object flatShift_jsonweave() {
        return flatWeave.apply(flatInputWeave);
    }

    // ------------------------------------------------------------ nested array restructuring

    @Benchmark
    public Object nestedShift_jolt() {
        return nestedJolt.transform(nestedInputJolt);
    }

    @Benchmark
    public Object nestedShift_jsonweave() {
        return nestedWeave.apply(nestedInputWeave);
    }

    // ------------------------------------------------------------ filter/sort/aggregate (Jolt cannot express this)

    @Benchmark
    public Object pipeline_jsonweave() {
        return pipelineWeave.apply(largeInputWeave);
    }

    @Benchmark
    public Object pipeline_handwrittenBaseline() {
        JsonNode orders = largeInputWeave.get("orders");
        List<JsonNode> shipped = new ArrayList<>();
        double revenue = 0;
        for (JsonNode o : orders) {
            if (!"SHIPPED".equals(o.get("status").asText())) {
                continue;
            }
            revenue += o.get("total").asDouble();
            if (o.get("total").asDouble() > 100) {
                shipped.add(o);
            }
        }
        shipped.sort(Comparator.comparingDouble(o -> -o.get("total").asDouble()));
        ArrayNode topIds = MAPPER.createArrayNode();
        shipped.stream().limit(10).forEach(o -> topIds.add(o.get("id")));
        ObjectNode out = MAPPER.createObjectNode();
        out.set("topIds", topIds);
        out.put("revenue", revenue);
        return out;
    }
}
