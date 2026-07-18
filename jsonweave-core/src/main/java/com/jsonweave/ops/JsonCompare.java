package com.jsonweave.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;

import java.math.BigDecimal;
import java.util.Comparator;

/**
 * Numeric-aware equality and a total ordering over JSON values, shared by
 * {@code #filter}, {@code #sortBy}, {@code #distinct}, {@code #min}/{@code #max}.
 */
public final class JsonCompare {

    private JsonCompare() {
    }

    /** Treats 1, 1.0 and 1E0 as equal, including inside nested containers. */
    private static final Comparator<JsonNode> NUMERIC_AWARE = (a, b) -> {
        if (a.equals(b)) {
            return 0;
        }
        if (a.isNumber() && b.isNumber()) {
            return compareNumbers(a, b);
        }
        return 1;
    };

    /** Long/double fast paths; BigDecimal only when a node actually carries one. */
    private static int compareNumbers(JsonNode a, JsonNode b) {
        if (a.isBigDecimal() || b.isBigDecimal() || a.isBigInteger() || b.isBigInteger()) {
            return a.decimalValue().compareTo(b.decimalValue());
        }
        if (a.isIntegralNumber() && b.isIntegralNumber()) {
            return Long.compare(a.longValue(), b.longValue());
        }
        return Double.compare(a.doubleValue(), b.doubleValue());
    }

    public static boolean equalsNode(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.equals(NUMERIC_AWARE, b);
    }

    /**
     * Total order for sorting: null &lt; numbers &lt; strings &lt; booleans &lt; arrays &lt; objects,
     * with natural ordering within numbers and strings. Containers order by their JSON text,
     * which is arbitrary but stable.
     */
    public static int compare(JsonNode a, JsonNode b) {
        int ra = rank(a);
        int rb = rank(b);
        if (ra != rb) {
            return Integer.compare(ra, rb);
        }
        return switch (ra) {
            case 1 -> compareNumbers(a, b);
            case 2 -> a.textValue().compareTo(b.textValue());
            case 3 -> Boolean.compare(a.booleanValue(), b.booleanValue());
            case 4, 5 -> a.toString().compareTo(b.toString());
            default -> 0;
        };
    }

    private static int rank(JsonNode n) {
        if (n.isNull()) {
            return 0;
        }
        if (n.isNumber()) {
            return 1;
        }
        if (n.isTextual()) {
            return 2;
        }
        if (n.isBoolean()) {
            return 3;
        }
        if (n.isArray()) {
            return 4;
        }
        return 5;
    }

    /** Canonical key for {@code #distinct}/{@code #groupBy}-style hashing of numeric variants. */
    public static Object canonicalKey(JsonNode n) {
        if (n.isNumber()) {
            BigDecimal d = n.decimalValue().stripTrailingZeros();
            return d.scale() < 0 ? d.setScale(0) : d;
        }
        return n;
    }

    /** Emits a number node in its simplest form: integral values fitting a long become longs. */
    public static JsonNode numberNode(BigDecimal value) {
        BigDecimal d = value.stripTrailingZeros();
        if (d.scale() < 0) {
            d = d.setScale(0);
        }
        if (d.scale() <= 0) {
            try {
                long l = d.longValueExact();
                return l == (int) l ? IntNode.valueOf((int) l) : LongNode.valueOf(l);
            } catch (ArithmeticException tooBig) {
                return DecimalNode.valueOf(d);
            }
        }
        double dv = d.doubleValue();
        if (BigDecimal.valueOf(dv).compareTo(d) == 0) {
            return DoubleNode.valueOf(dv);
        }
        return DecimalNode.valueOf(d);
    }
}
