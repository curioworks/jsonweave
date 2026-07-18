package com.jsonweave.path;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsonweave.spec.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * A compiled data reference. Grammar:
 * <pre>
 *   $              document root
 *   $.a.b[0].c     root-anchored path
 *   @              current element (inside per-element scopes)
 *   @.a[2]         element-anchored path
 *   $name.a        #let variable reference ($ followed by an identifier)
 *   $.["a.b"]      bracket-quoted key (for keys containing dots, spaces, ...)
 *   [-1]           negative indexes count from the end
 * </pre>
 * Segments are parsed once at compile time; {@link #resolve} is a plain loop of
 * field/index hops with no string work.
 */
public final class JsonPath {

    public enum Anchor { ROOT, CURRENT, VAR }

    private final Anchor anchor;
    private final String varName;      // only for VAR
    private final Object[] segments;   // String field names and Integer indexes

    private JsonPath(Anchor anchor, String varName, Object[] segments) {
        this.anchor = anchor;
        this.varName = varName;
        this.segments = segments;
    }

    public Anchor anchor() {
        return anchor;
    }

    public String varName() {
        return varName;
    }

    /**
     * Whether {@code s} is a data reference rather than a literal string. Only these
     * shapes qualify: {@code $}, {@code $.…}, {@code $[…}, {@code @}, {@code @.…},
     * {@code @[…} and {@code $identifier…}. Everything else ("hello", "$100",
     * "user@host") is a literal.
     */
    public static boolean isPath(String s) {
        if (s.isEmpty()) {
            return false;
        }
        char c0 = s.charAt(0);
        if (c0 == '@') {
            return s.length() == 1 || s.charAt(1) == '.' || s.charAt(1) == '[';
        }
        if (c0 == '$') {
            if (s.length() == 1 || s.charAt(1) == '.' || s.charAt(1) == '[') {
                return true;
            }
            return isIdentStart(s.charAt(1)); // variable reference
        }
        return false;
    }

    /** Parses a full path (must satisfy {@link #isPath}). Throws {@link IllegalArgumentException} on bad syntax. */
    public static JsonPath parse(String s) {
        if (!isPath(s)) {
            throw new IllegalArgumentException("not a path: '" + s + "'");
        }
        char c0 = s.charAt(0);
        if (c0 == '@') {
            return new JsonPath(Anchor.CURRENT, null, parseSegments(s, 1));
        }
        // '$'
        if (s.length() == 1 || s.charAt(1) == '.' || s.charAt(1) == '[') {
            return new JsonPath(Anchor.ROOT, null, parseSegments(s, 1));
        }
        int i = 1;
        while (i < s.length() && isIdentPart(s.charAt(i))) {
            i++;
        }
        String name = s.substring(1, i);
        return new JsonPath(Anchor.VAR, name, parseSegments(s, i));
    }

    /**
     * Parses a path in a position that is inherently a path (match-object keys,
     * {@code #sortBy}, {@code #groupBy}, ...). Anchors are required everywhere —
     * there is no bare-relative shorthand — so a plain field name fails with a
     * fix-it hint instead of being silently interpreted.
     */
    public static JsonPath parseKeyPath(String s) {
        if (isPath(s)) {
            return parse(s);
        }
        throw new IllegalArgumentException("'" + s + "' is not a path; use '@." + s
                + "' for a field of the current element, or '$." + s + "' for a root field");
    }

    private static Object[] parseSegments(String s, int i) {
        List<Object> segs = new ArrayList<>();
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '.') {
                i++;
                if (i < n && s.charAt(i) == '[') {
                    i = parseBracket(s, i, segs);
                } else {
                    int start = i;
                    while (i < n && isNameChar(s.charAt(i))) {
                        i++;
                    }
                    if (i == start) {
                        throw new IllegalArgumentException("expected a field name at offset " + start + " in '" + s + "'");
                    }
                    segs.add(s.substring(start, i));
                }
            } else if (c == '[') {
                i = parseBracket(s, i, segs);
            } else {
                throw new IllegalArgumentException("unexpected character '" + c + "' at offset " + i + " in '" + s + "'");
            }
        }
        return segs.toArray();
    }

    /** Parses either ["quoted key"] or [int]; returns the index just past the closing bracket. */
    private static int parseBracket(String s, int i, List<Object> segs) {
        int n = s.length();
        i++; // past '['
        if (i < n && s.charAt(i) == '"') {
            int end = s.indexOf('"', i + 1);
            if (end < 0 || end + 1 >= n || s.charAt(end + 1) != ']') {
                throw new IllegalArgumentException("unterminated [\"…\"] segment in '" + s + "'");
            }
            segs.add(s.substring(i + 1, end));
            return end + 2;
        }
        int start = i;
        if (i < n && s.charAt(i) == '-') {
            i++;
        }
        while (i < n && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i == start || (s.charAt(start) == '-' && i == start + 1) || i >= n || s.charAt(i) != ']') {
            throw new IllegalArgumentException("expected [integer] or [\"key\"] at offset " + start + " in '" + s + "'");
        }
        segs.add(Integer.parseInt(s.substring(start, i)));
        return i + 1;
    }

    /** Resolves against the context; returns {@code null} for missing. */
    public JsonNode resolve(Context ctx) {
        JsonNode node = switch (anchor) {
            case ROOT -> ctx.root();
            case CURRENT -> ctx.currentOrRoot();
            case VAR -> ctx.var(varName);
        };
        for (Object seg : segments) {
            if (node == null || node.isMissingNode()) {
                return null;
            }
            if (seg instanceof String field) {
                node = node.isObject() ? node.get(field) : null;
            } else {
                int idx = (Integer) seg;
                if (!node.isArray()) {
                    node = null;
                } else {
                    node = node.get(idx >= 0 ? idx : node.size() + idx);
                }
            }
        }
        return node == null || node.isMissingNode() ? null : node;
    }

    public static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    public static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }
}
