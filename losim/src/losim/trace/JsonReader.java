package losim.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads back what {@link Json} wrote.
 *
 * <p>Enough JSON for a trace and a cached scale plan: objects, arrays, strings,
 * numbers, booleans and null. Numbers come back as {@code Double} and structures as
 * {@code LinkedHashMap} and {@code ArrayList}, so key order survives a round trip —
 * which two traces being diffable depends on.
 */
public final class JsonReader {

    private final String text;
    private int at;

    private JsonReader(String text) { this.text = text; }

    public static Object read(String json) {
        var r = new JsonReader(json);
        r.skipSpace();
        Object value = r.value();
        r.skipSpace();
        if (r.at < r.text.length())
            throw new IllegalArgumentException("trailing content at offset " + r.at);
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String json) {
        Object v = read(json);
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected an object");
        return (Map<String, Object>) v;
    }

    private Object value() {
        char c = peek();
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default  -> number();
        };
    }

    private Map<String, Object> object() {
        var out = new LinkedHashMap<String, Object>();
        expect('{');
        skipSpace();
        if (peek() == '}') { at++; return out; }
        while (true) {
            skipSpace();
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            out.put(key, value());
            skipSpace();
            char c = text.charAt(at++);
            if (c == '}') return out;
            if (c != ',') throw fail("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        var out = new ArrayList<>();
        expect('[');
        skipSpace();
        if (peek() == ']') { at++; return out; }
        while (true) {
            skipSpace();
            out.add(value());
            skipSpace();
            char c = text.charAt(at++);
            if (c == ']') return out;
            if (c != ',') throw fail("expected ',' or ']'");
        }
    }

    private String string() {
        expect('"');
        var sb = new StringBuilder();
        while (true) {
            char c = text.charAt(at++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = text.charAt(at++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> { sb.append((char) Integer.parseInt(text.substring(at, at + 4), 16)); at += 4; }
                default  -> sb.append(e);
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-.eE0123456789".indexOf(text.charAt(at)) >= 0) at++;
        if (start == at) throw fail("expected a value");
        return Double.parseDouble(text.substring(start, at));
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) throw fail("expected " + word);
        at += word.length();
        return value;
    }

    private char peek() {
        if (at >= text.length()) throw fail("unexpected end of input");
        return text.charAt(at);
    }

    private void expect(char c) {
        if (peek() != c) throw fail("expected '" + c + "'");
        at++;
    }

    private void skipSpace() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }

    private IllegalArgumentException fail(String what) {
        return new IllegalArgumentException(what + " at offset " + at);
    }
}
