package losim.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A YAML subset: nested mappings, sequences, inline {a: b} and [a, b], comments.
 *
 * Deliberately small. The scenario schema is declarative data — no expressions,
 * no arithmetic, no conditionals — so a full YAML engine would be more power
 * than the format is allowed to use.
 */
public final class Yaml {

    private final List<String> lines;
    private final String file;
    private int pos;

    private Yaml(String text, String file) {
        this.lines = new ArrayList<>(List.of(text.split("\n", -1)));
        this.file = file;
    }

    public static Node parse(String text, String file) {
        Yaml y = new Yaml(text, file);
        Node n = y.parseBlock(0);
        return n == null ? Node.empty(file) : n;
    }

    private record Line(int indent, String content, int number) {}

    private Line peek() {
        while (pos < lines.size()) {
            String raw = lines.get(pos);
            String noComment = stripComment(raw);
            if (noComment.isBlank()) { pos++; continue; }
            int indent = 0;
            while (indent < noComment.length() && noComment.charAt(indent) == ' ') indent++;
            return new Line(indent, noComment.substring(indent).stripTrailing(), pos + 1);
        }
        return null;
    }

    private static String stripComment(String s) {
        boolean inQuote = false;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) { if (c == q) inQuote = false; }
            else if (c == '"' || c == '\'') { inQuote = true; q = c; }
            else if (c == '#' && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) return s.substring(0, i);
        }
        return s;
    }

    private Node parseBlock(int minIndent) {
        Line first = peek();
        if (first == null || first.indent() < minIndent) return null;
        return first.content().startsWith("- ") || first.content().equals("-")
                ? parseSeq(first.indent())
                : parseMap(first.indent());
    }

    private Node parseMap(int indent) {
        Map<String, Node> out = new LinkedHashMap<>();
        int startLine = -1;
        while (true) {
            Line l = peek();
            if (l == null || l.indent() < indent) break;
            if (l.indent() > indent) throw new Node.ConfigError(
                    file + ":" + l.number() + ": unexpected indentation");
            if (l.content().startsWith("- ")) break;
            if (startLine < 0) startLine = l.number();

            int colon = findColon(l.content());
            if (colon < 0) throw new Node.ConfigError(
                    file + ":" + l.number() + ": expected 'key: value'");
            String key = l.content().substring(0, colon).trim();
            String rest = l.content().substring(colon + 1).trim();
            pos++;
            if (rest.equals(">") || rest.equals("|")) {
                out.put(key, blockScalar(rest.equals(">"), indent, l.number()));
            } else if (rest.isEmpty()) {
                Node child = parseBlock(indent + 1);
                out.put(key, child != null ? child : new Node(null, l.number(), file));
            } else {
                out.put(key, scalarOrInline(rest, l.number()));
            }
        }
        return new Node(out, startLine < 0 ? 1 : startLine, file);
    }

    /** Block scalars: 'key: >' folds lines into one, 'key: |' keeps the newlines. */
    private Node blockScalar(boolean folded, int parentIndent, int line) {
        List<String> collected = new ArrayList<>();
        while (pos < lines.size()) {
            String raw = lines.get(pos);
            if (raw.isBlank()) { collected.add(""); pos++; continue; }
            int ind = 0;
            while (ind < raw.length() && raw.charAt(ind) == ' ') ind++;
            if (ind <= parentIndent) break;
            collected.add(raw.substring(ind).stripTrailing());
            pos++;
        }
        while (!collected.isEmpty() && collected.get(collected.size() - 1).isBlank())
            collected.remove(collected.size() - 1);
        String text = folded ? String.join(" ", collected) : String.join("\n", collected);
        return new Node(text.strip(), line, file);
    }

    private Node parseSeq(int indent) {
        List<Node> out = new ArrayList<>();
        int startLine = -1;
        while (true) {
            Line l = peek();
            if (l == null || l.indent() < indent || !(l.content().startsWith("- ") || l.content().equals("-"))) break;
            if (startLine < 0) startLine = l.number();
            String rest = l.content().equals("-") ? "" : l.content().substring(2).trim();
            pos++;
            if (rest.isEmpty()) {
                Node child = parseBlock(indent + 1);
                out.add(child != null ? child : new Node(null, l.number(), file));
            } else if (findColon(rest) >= 0 && !rest.startsWith("{") && !rest.startsWith("[")) {
                // "- key: value" starts a mapping that may continue on following lines
                Map<String, Node> m = new LinkedHashMap<>();
                int colon = findColon(rest);
                String k = rest.substring(0, colon).trim();
                String v = rest.substring(colon + 1).trim();
                m.put(k, v.isEmpty() ? new Node(null, l.number(), file) : scalarOrInline(v, l.number()));
                Node more = parseBlockAt(indent + 2);
                if (more != null && more.isMap()) m.putAll(more.map());
                out.add(new Node(m, l.number(), file));
            } else {
                out.add(scalarOrInline(rest, l.number()));
            }
        }
        return new Node(out, startLine < 0 ? 1 : startLine, file);
    }

    private Node parseBlockAt(int indent) {
        Line l = peek();
        if (l == null || l.indent() != indent) return null;
        return parseMap(indent);
    }

    private int findColon(String s) {
        int depth = 0;
        boolean inQuote = false; char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) { if (c == q) inQuote = false; continue; }
            if (c == '"' || c == '\'') { inQuote = true; q = c; }
            else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == ':' && depth == 0 && (i + 1 == s.length() || s.charAt(i + 1) == ' ')) return i;
        }
        return -1;
    }

    private Node scalarOrInline(String s, int line) {
        String t = s.trim();
        if (t.startsWith("{") && t.endsWith("}")) {
            Map<String, Node> m = new LinkedHashMap<>();
            for (String part : splitTop(t.substring(1, t.length() - 1))) {
                if (part.isBlank()) continue;
                int c = findColon(part);
                if (c < 0) throw new Node.ConfigError(file + ":" + line + ": expected 'key: value' in { }");
                m.put(part.substring(0, c).trim(), scalarOrInline(part.substring(c + 1).trim(), line));
            }
            return new Node(m, line, file);
        }
        if (t.startsWith("[") && t.endsWith("]")) {
            List<Node> l = new ArrayList<>();
            for (String part : splitTop(t.substring(1, t.length() - 1))) {
                if (part.isBlank()) continue;
                l.add(scalarOrInline(part.trim(), line));
            }
            return new Node(l, line, file);
        }
        return new Node(unquote(t), line, file);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))))
            return s.substring(1, s.length() - 1);
        return s;
    }

    private static List<String> splitTop(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        boolean inQuote = false; char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) { if (c == q) inQuote = false; continue; }
            if (c == '"' || c == '\'') { inQuote = true; q = c; }
            else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out;
    }
}
