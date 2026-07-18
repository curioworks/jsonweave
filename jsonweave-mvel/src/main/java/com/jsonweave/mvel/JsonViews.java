package com.jsonweave.mvel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Lazy {@link Map}/{@link java.util.List} views over Jackson trees, so MVEL expressions
 * navigate documents with zero up-front conversion — only the properties an expression
 * actually touches cross the boundary, unwrapped to plain Java scalars.
 */
final class JsonViews {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonViews() {
    }

    /** JsonNode -> value MVEL can work with (scalars unwrapped, containers as lazy views). */
    static Object unwrap(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (n.isObject()) {
            return new MapView((ObjectNode) n);
        }
        if (n.isArray()) {
            return new ListView((ArrayNode) n);
        }
        if (n.isTextual()) {
            return n.textValue();
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isNumber()) {
            return n.numberValue(); // Integer/Long/Double/BigDecimal/... as parsed
        }
        return n.asText(); // binary and friends
    }

    /** Expression result -> JsonNode; {@code null} stays null (= missing). */
    static JsonNode toNode(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof MapView v) {
            return v.node;
        }
        if (o instanceof ListView v) {
            return v.node;
        }
        if (o instanceof JsonNode n) {
            return n;
        }
        return MAPPER.valueToTree(o);
    }

    static final class MapView extends AbstractMap<String, Object> {
        final ObjectNode node;

        MapView(ObjectNode node) {
            this.node = node;
        }

        @Override
        public Object get(Object key) {
            return unwrap(node.get((String) key));
        }

        @Override
        public boolean containsKey(Object key) {
            return node.has((String) key);
        }

        @Override
        public int size() {
            return node.size();
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, Object>> iterator() {
                    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return fields.hasNext();
                        }

                        @Override
                        public Entry<String, Object> next() {
                            Map.Entry<String, JsonNode> e = fields.next();
                            return new SimpleImmutableEntry<>(e.getKey(), unwrap(e.getValue()));
                        }
                    };
                }

                @Override
                public int size() {
                    return node.size();
                }
            };
        }
    }

    static final class ListView extends AbstractList<Object> {
        final ArrayNode node;

        ListView(ArrayNode node) {
            this.node = node;
        }

        @Override
        public Object get(int index) {
            return unwrap(node.get(index));
        }

        @Override
        public int size() {
            return node.size();
        }
    }
}
