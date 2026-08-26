package losim.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Enough YAML for a scenario, and deliberately not one feature more.
 *
 * <p>Block maps, block lists, inline maps and inline lists, comments, and quoted
 * strings. No anchors, no references, no multi-document files, no folded scalars —
 * a scenario is meant to be read at a glance by someone who did not write it, and
 * every one of those features exists to make a file shorter at the cost of being
 * readable.
 *
 * <p>Refusing them is also what keeps this small enough to carry a line number on
 * every value, which is what {@link Node#where()} needs.
 */
public final class Yaml {
    private Yaml() {}

    public static Node parse(Path file) throws IOException {
        return parse(file.getFileName().toString(), Files.readString(file));
    }

    public static Node parse(String name, String text) {
        var lines = new ArrayList<Line>();
        int no = 0;
        for (String raw : text.split("\n", -1)) {
            no++;
            String stripped = stripComment(raw);
            if (stripped.isBlank()) continue;
            lines.add(new Line(no, indentOf(stripped), stripped.trim()));
        }
        var cursor = new int[]{0};
        Node root = Node.map(name, 1);
        if (lines.isEmpty()) return root;
        readBlock(name, lines, cursor, lines.get(0).indent(), root);
        if (cursor[0] < lines.size())
            throw new IllegalArgumentException(name + ":" + lines.get(cursor[0]).no()
                    + ": this line is indented less than the block it is in");
        return root;
    }

    private record Line(int no, int indent, String text) {}

    /**
     * Reads every line at one indentation into {@code into}, recursing for deeper ones.
     */
    private static void readBlock(String file, List<Line> lines, int[] at, int indent, Node into) {
        while (at[0] < lines.size()) {
            Line line = lines.get(at[0]);
            if (line.indent() < indent) return;
            if (line.indent() > indent)
                throw new IllegalArgumentException(file + ":" + line.no()
                        + ": unexpected indentation — nothing here opens a block");
            at[0]++;

            if (line.text().startsWith("- ") || line.text().equals("-")) {
                if (!into.isList())
                    throw new IllegalArgumentException(file + ":" + line.no()
                            + ": a list item here, but the enclosing key expects named values");
                String rest = line.text().equals("-") ? "" : line.text().substring(2).trim();
                into.add(readValue(file, lines, at, line, indent, rest, true));
                continue;
            }

            int colon = colonAt(line.text());
            if (colon < 0)
                throw new IllegalArgumentException(file + ":" + line.no()
                        + ": expected 'key: value' or a '- ' list item, got '" + line.text() + "'");
            String key = unquote(line.text().substring(0, colon).trim());
            String rest = line.text().substring(colon + 1).trim();
            if (!into.isMap())
                throw new IllegalArgumentException(file + ":" + line.no()
                        + ": a named value here, but the enclosing key expects a list");
            into.put(key, readValue(file, lines, at, line, indent, rest, false));
        }
    }

    /** The value after a key, which is either on this line or in the block below it. */
    private static Node readValue(String file, List<Line> lines, int[] at, Line line,
                                  int indent, String rest, boolean inList) {
        if (!rest.isEmpty()) {
            // A list item may itself be a key: value pair, and may open a block.
            if (inList && colonAt(rest) >= 0 && !rest.startsWith("{") && !rest.startsWith("[")) {
                Node m = Node.map(file, line.no());
                int colon = colonAt(rest);
                String key = unquote(rest.substring(0, colon).trim());
                String tail = rest.substring(colon + 1).trim();
                m.put(key, tail.isEmpty()
                        ? childBlock(file, lines, at, indent, line)
                        : inline(file, line.no(), tail));
                // Sibling keys of the same item are indented past the dash.
                if (at[0] < lines.size() && lines.get(at[0]).indent() > indent)
                    readBlock(file, lines, at, lines.get(at[0]).indent(), m);
                return m;
            }
            return inline(file, line.no(), rest);
        }
        return childBlock(file, lines, at, indent, line);
    }

    /** The indented block beneath a key with no value on its own line. */
    private static Node childBlock(String file, List<Line> lines, int[] at, int indent, Line line) {
        if (at[0] >= lines.size() || lines.get(at[0]).indent() <= indent)
            throw new IllegalArgumentException(file + ":" + line.no()
                    + ": '" + line.text() + "' has no value and nothing indented under it");
        Line first = lines.get(at[0]);
        Node child = first.text().startsWith("-") ? Node.list(file, first.no())
                                                  : Node.map(file, first.no());
        readBlock(file, lines, at, first.indent(), child);
        return child;
    }

    // ------------------------------------------------------------------- inline

    private static Node inline(String file, int no, String text) {
        String t = text.trim();
        if (t.startsWith("{") && t.endsWith("}")) {
            Node m = Node.map(file, no);
            for (String part : split(t.substring(1, t.length() - 1))) {
                if (part.isBlank()) continue;
                int colon = colonAt(part);
                if (colon < 0)
                    throw new IllegalArgumentException(file + ":" + no
                            + ": '" + part.trim() + "' is missing its ':'");
                m.put(unquote(part.substring(0, colon).trim()),
                      inline(file, no, part.substring(colon + 1).trim()));
            }
            return m;
        }
        if (t.startsWith("[") && t.endsWith("]")) {
            Node l = Node.list(file, no);
            for (String part : split(t.substring(1, t.length() - 1)))
                if (!part.isBlank()) l.add(inline(file, no, part.trim()));
            return l;
        }
        return Node.scalar(file, no, unquote(t));
    }

    /** Splits on commas that are not inside braces, brackets or quotes. */
    private static List<String> split(String s) {
        var out = new ArrayList<String>();
        int depth = 0;
        char quote = 0;
        var sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (quote != 0) {
                sb.append(c);
                if (c == quote) quote = 0;
                continue;
            }
            switch (c) {
                case '"', '\'' -> { quote = c; sb.append(c); }
                case '{', '[' -> { depth++; sb.append(c); }
                case '}', ']' -> { depth--; sb.append(c); }
                case ',' -> { if (depth == 0) { out.add(sb.toString()); sb.setLength(0); } else sb.append(c); }
                default -> sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }

    // -------------------------------------------------------------------- lexing

    /** The first colon that separates a key from a value, ignoring quotes and brackets. */
    private static int colonAt(String s) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            switch (c) {
                case '"', '\'' -> quote = c;
                case '{', '[' -> depth++;
                case '}', ']' -> depth--;
                case ':' -> {
                    if (depth == 0 && (i + 1 == s.length() || s.charAt(i + 1) == ' ')) return i;
                }
                default -> { }
            }
        }
        return -1;
    }

    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '#' && (i == 0 || line.charAt(i - 1) == ' ')) return line.substring(0, i);
        }
        return line;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        if (i < line.length() && line.charAt(i) == '\t')
            throw new IllegalArgumentException("tabs cannot be used to indent YAML");
        return i;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'')
            && s.charAt(s.length() - 1) == s.charAt(0))
            return s.substring(1, s.length() - 1);
        return s;
    }
}
