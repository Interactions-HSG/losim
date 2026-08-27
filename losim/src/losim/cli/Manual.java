package losim.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import losim.trace.JsonReader;

/**
 * The manual, rendered here rather than by a previewer that has to be fetched.
 *
 * <p>This started as a call to Mintlify's CLI, which is what the pages are
 * authored for. It is not that any more, for one reason: on the day this was
 * written {@code npx mint dev} failed for everybody, because one of its
 * transitive dependencies had gone from the registry. A student opening a
 * Codespace would have met a 404 as this course's first words — and would have
 * had no way of knowing the failure was not theirs.
 *
 * <p>So the manual is rendered by the JDK that is already here. That buys three
 * things worth more than perfect fidelity to the authored design: it works
 * offline, it cannot break because somebody else published something, and it
 * removes the last reason for node to be in the container at all.
 *
 * <p><b>What this is not.</b> It is not an MDX implementation. It renders the
 * Markdown these pages are mostly made of, and maps the dozen components they
 * actually use onto plain semantic HTML — a {@code <Note>} is a callout, a
 * {@code <Steps>} is an ordered list, an {@code <Accordion>} is a
 * {@code <details>}. A component nobody used is not supported, and an unknown
 * one keeps its contents and loses its box, because losing a paragraph is worse
 * than losing a border. The authored site remains the published one; this is the
 * copy that is beside the work.
 */
public final class Manual {

    private final Path root;
    private final List<Group> nav = new ArrayList<>();
    private String title = "Manual";

    /** One group in the sidebar, and the pages under it. */
    private record Group(String tab, String name, List<Page> pages) { }

    private record Page(String href, String label) { }

    public Manual(Path root) {
        this.root = root;
        try {
            read(JsonReader.readObject(Files.readString(root.resolve("docs.json"))));
        } catch (Exception e) {
            // No docs.json is not a failure: the pages are still files, and a flat
            // list of them is a worse sidebar rather than no manual.
            scan();
        }
    }

    public boolean present() { return Files.isDirectory(root); }

    // -------------------------------------------------------------- navigation

    @SuppressWarnings("unchecked")
    private void read(Map<String, Object> doc) {
        if (doc.get("name") instanceof String n) title = n;
        Object navigation = doc.get("navigation");
        if (!(navigation instanceof Map<?, ?> m)) { scan(); return; }
        Object tabs = m.get("tabs");
        if (tabs instanceof List<?> list) {
            for (Object t : list) {
                if (!(t instanceof Map<?, ?> tab)) continue;
                String name = tab.get("tab") == null ? "" : String.valueOf(tab.get("tab"));
                groups(name, (List<Object>) tab.get("groups"));
            }
        } else {
            groups("", (List<Object>) m.get("groups"));
        }
        if (nav.isEmpty()) scan();
    }

    @SuppressWarnings("unchecked")
    private void groups(String tab, List<Object> groups) {
        if (groups == null) return;
        for (Object g : groups) {
            if (!(g instanceof Map<?, ?> group)) continue;
            String name = group.get("group") == null ? "" : String.valueOf(group.get("group"));
            List<Page> pages = new ArrayList<>();
            Object listed = group.get("pages");
            for (Object p : listed instanceof List<?> l ? l : List.of()) {
                if (p instanceof String s) pages.add(new Page(s, label(s)));
                // A nested group becomes a group of its own rather than an
                // indent: two levels of sidebar is one more than this needs.
                else if (p instanceof Map<?, ?> sub) groups(tab, List.of(sub));
            }
            if (!pages.isEmpty()) nav.add(new Group(tab, name, pages));
        }
    }

    /** Every page on disk, when there is no table of contents to follow. */
    private void scan() {
        nav.clear();
        List<Page> pages = new ArrayList<>();
        try (var s = Files.walk(root)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".mdx")).sorted().toList()) {
                String href = root.relativize(p).toString().replaceAll("\\.mdx$", "");
                pages.add(new Page(href, label(href)));
            }
        } catch (IOException ignored) { /* an unreadable manual is an empty one */ }
        if (!pages.isEmpty()) nav.add(new Group("", "Pages", pages));
    }

    private static String label(String href) {
        String last = href.contains("/") ? href.substring(href.lastIndexOf('/') + 1) : href;
        if (last.equals("index")) last = "Overview";
        last = last.replace('-', ' ');
        return Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    /** Where the sidebar points first. */
    public String home() {
        return nav.isEmpty() ? "index" : nav.get(0).pages().get(0).href();
    }

    // ------------------------------------------------------------------ serving

    /** The rendered page at this path, or null when there is no such page. */
    public byte[] page(String path) {
        String href = path.isEmpty() || path.equals("/") ? home() : path;
        href = href.replaceAll("^/+", "").replaceAll("/+$", "");
        if (href.contains("..")) return null;

        Path file = root.resolve(href + ".mdx");
        if (!Files.isRegularFile(file)) file = root.resolve(href + ".md");
        if (!Files.isRegularFile(file)) file = root.resolve(href).resolve("index.mdx");
        if (!Files.isRegularFile(file)) return null;

        String source;
        try { source = Files.readString(file); }
        catch (IOException e) { return null; }

        Map<String, String> front = new LinkedHashMap<>();
        source = frontmatter(source, front);
        String body = new Render(depth(href)).run(source);
        return shell(href, front, body).getBytes(StandardCharsets.UTF_8);
    }

    /** How many `../` it takes to get back to the manual's root from a page. */
    private static int depth(String href) {
        int n = 0;
        for (int i = 0; i < href.length(); i++) if (href.charAt(i) == '/') n++;
        return n;
    }

    /** Strip and read the YAML-ish header these pages carry. */
    private static String frontmatter(String source, Map<String, String> into) {
        if (!source.startsWith("---")) return source;
        int end = source.indexOf("\n---", 3);
        if (end < 0) return source;
        for (String line : source.substring(3, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String v = line.substring(colon + 1).trim();
            if (v.length() > 1 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
            into.put(line.substring(0, colon).trim(), v);
        }
        int nl = source.indexOf('\n', end + 1);
        return nl < 0 ? "" : source.substring(nl + 1);
    }

    private String shell(String href, Map<String, String> front, String body) {
        String up = "../".repeat(depth(href));
        StringBuilder side = new StringBuilder();
        String tab = null;
        for (Group g : nav) {
            if (!g.tab().equals(tab)) {
                tab = g.tab();
                if (!tab.isEmpty()) side.append("<h4>").append(esc(tab)).append("</h4>");
            }
            side.append("<h5>").append(esc(g.name())).append("</h5><ul>");
            for (Page p : g.pages()) {
                boolean here = p.href().equals(href);
                side.append("<li><a class=\"").append(here ? "here" : "")
                    .append("\" href=\"").append(up).append(esc(p.href())).append("\">")
                    .append(esc(p.label())).append("</a></li>");
            }
            side.append("</ul>");
        }

        String heading = front.getOrDefault("title", label(href));
        String lede = front.getOrDefault("description", "");
        return """
            <!doctype html>
            <html lang="en"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>%s — %s</title>
            <style>%s</style>
            </head><body>
            <nav class="side"><a class="brand" href="%s%s">%s</a>%s</nav>
            <main>
              <header><h1>%s</h1>%s</header>
              %s
            </main>
            </body></html>
            """.formatted(esc(heading), esc(title), CSS, up, esc(home()), esc(title), side,
                          esc(heading), lede.isEmpty() ? "" : "<p class=\"lede\">" + esc(lede) + "</p>",
                          body);
    }

    /**
     * Deliberately plain, and deliberately not a reproduction of the published
     * site: this is the copy you read beside the code, and its job is to be
     * legible at a glance in a narrow panel next to an editor.
     */
    private static final String CSS = """
        *{box-sizing:border-box}
        :root{--ink:#16211f;--dim:#5c6b68;--rule:#dfe5e3;--bg:#fbfcfc;--surface:#fff;
              --accent:#1F6F63;--accent-soft:#e8f2f0;--code:#f4f6f6}
        @media (prefers-color-scheme:dark){:root{--ink:#e6ecea;--dim:#9fb0ac;--rule:#2a3634;
              --bg:#111817;--surface:#161f1e;--accent:#5CC0AE;--accent-soft:#18302c;--code:#1b2523}}
        body{margin:0;display:flex;background:var(--bg);color:var(--ink);
             font:16px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
        .side{width:16rem;flex:none;height:100vh;overflow:auto;padding:1.4rem 1rem;
              border-right:1px solid var(--rule);background:var(--surface)}
        .side .brand{display:block;font-weight:650;font-size:1.05rem;color:var(--ink);
              text-decoration:none;margin-bottom:1.2rem}
        .side h4{margin:1.4rem 0 .3rem;font-size:.7rem;letter-spacing:.09em;text-transform:uppercase;color:var(--dim)}
        .side h5{margin:1rem 0 .25rem;font-size:.78rem;font-weight:600;color:var(--dim)}
        .side ul{list-style:none;margin:0;padding:0}
        .side li a{display:block;padding:.2rem .45rem;border-radius:.3rem;font-size:.88rem;
              color:var(--ink);text-decoration:none}
        .side li a:hover{background:var(--accent-soft)}
        .side li a.here{background:var(--accent-soft);color:var(--accent);font-weight:600}
        main{flex:1;min-width:0;max-width:52rem;padding:2.4rem 2.6rem 6rem}
        header h1{margin:0;font-size:2rem;line-height:1.2;letter-spacing:-.02em}
        .lede{margin:.5rem 0 0;color:var(--dim);font-size:1.05rem}
        header{margin-bottom:2rem;padding-bottom:1.2rem;border-bottom:1px solid var(--rule)}
        h2{margin:2.4rem 0 .6rem;font-size:1.4rem;letter-spacing:-.01em}
        h3{margin:1.8rem 0 .4rem;font-size:1.1rem}
        h4{margin:1.2rem 0 .3rem;font-size:.98rem}
        p,li{overflow-wrap:anywhere}
        a{color:var(--accent)}
        code{background:var(--code);padding:.1rem .3rem;border-radius:.25rem;
             font:.88em ui-monospace,SFMono-Regular,Menlo,monospace}
        pre{background:var(--code);padding:.9rem 1rem;border-radius:.5rem;overflow-x:auto;
            border:1px solid var(--rule)}
        pre code{background:none;padding:0;font-size:.86rem;line-height:1.55}
        blockquote{margin:1.2rem 0;padding:.1rem 1rem;border-left:3px solid var(--accent);color:var(--dim)}
        table{border-collapse:collapse;width:100%;margin:1.2rem 0;font-size:.92rem;display:block;overflow-x:auto}
        th,td{border:1px solid var(--rule);padding:.45rem .6rem;text-align:left;vertical-align:top}
        th{background:var(--code);font-weight:600}
        .callout{margin:1.2rem 0;padding:.8rem 1rem;border-radius:.5rem;border:1px solid var(--rule);
                 background:var(--surface)}
        .callout>:first-child{margin-top:0}.callout>:last-child{margin-bottom:0}
        .callout .kind{display:block;font-size:.7rem;letter-spacing:.09em;text-transform:uppercase;
                 color:var(--dim);margin-bottom:.3rem}
        .callout.warning,.callout.danger{border-color:#c9822f;background:#c9822f14}
        .callout.tip,.callout.check{border-color:var(--accent);background:var(--accent-soft)}
        .cards{display:grid;gap:.9rem;grid-template-columns:repeat(auto-fit,minmax(15rem,1fr));margin:1.2rem 0}
        .card{display:block;padding:.9rem 1rem;border:1px solid var(--rule);border-radius:.5rem;
              background:var(--surface);text-decoration:none;color:inherit}
        .card .t{font-weight:640;margin-bottom:.25rem;color:var(--accent)}
        .card p{margin:.2rem 0;font-size:.92rem}
        details{margin:.5rem 0;border:1px solid var(--rule);border-radius:.5rem;background:var(--surface)}
        summary{padding:.6rem .9rem;cursor:pointer;font-weight:600}
        details>:not(summary){padding:0 .9rem}
        ol.steps{counter-reset:s;list-style:none;padding-left:0}
        ol.steps>li{position:relative;padding:0 0 .8rem 2.2rem;margin:0;border-left:1px solid var(--rule);
              padding-top:.1rem;margin-left:.9rem}
        ol.steps>li:before{counter-increment:s;content:counter(s);position:absolute;left:-.9rem;top:0;
              width:1.8rem;height:1.8rem;border-radius:50%;background:var(--accent-soft);color:var(--accent);
              display:grid;place-items:center;font-size:.8rem;font-weight:700}
        ol.steps>li>.t{font-weight:640;margin-bottom:.2rem}
        .field{margin:.7rem 0;padding-left:.8rem;border-left:2px solid var(--rule)}
        .field .n{font:.88rem ui-monospace,Menlo,monospace;font-weight:640}
        .field .ty{color:var(--dim);font-size:.82rem;margin-left:.4rem}
        .field .req{color:#c9822f;font-size:.72rem;margin-left:.4rem;text-transform:uppercase;letter-spacing:.06em}
        figure{margin:1.2rem 0}img{max-width:100%}
        @media (max-width:820px){body{display:block}.side{width:auto;height:auto;border-right:0;
              border-bottom:1px solid var(--rule)}main{padding:1.4rem}}
        """;

    // ------------------------------------------------------------- the renderer

    /**
     * Markdown, plus the components these pages use.
     *
     * <p>One pass over the lines, with a stack for what is open. Code fences are
     * checked first and swallow everything until they close — without that,
     * {@code List<String>} in a Java sample reads as an unknown component and its
     * line disappears.
     */
    private static final class Render {
        private final StringBuilder out = new StringBuilder();
        private final List<String> para = new ArrayList<>();
        private final Deque<String> open = new ArrayDeque<>();
        private final String up;

        Render(int depth) { this.up = "../".repeat(depth); }

        String run(String source) {
            String[] lines = source.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String trim = line.trim();

                if (trim.startsWith("```")) {
                    flush();
                    String lang = trim.substring(3).trim();
                    List<String> code = new ArrayList<>();
                    while (++i < lines.length && !lines[i].trim().startsWith("```")) code.add(lines[i]);
                    out.append("<pre><code class=\"lang-").append(esc(lang.split("\\s+")[0]))
                       .append("\">").append(esc(dedent(code))).append("</code></pre>");
                    continue;
                }

                if (trim.startsWith("</") && trim.endsWith(">")) { close(trim); continue; }
                if (trim.startsWith("<") && component(trim)) continue;

                if (trim.isEmpty()) { flush(); continue; }

                if (trim.startsWith("#")) {
                    flush();
                    int n = 0;
                    while (n < trim.length() && trim.charAt(n) == '#') n++;
                    int level = Math.min(6, Math.max(2, n));
                    out.append("<h").append(level).append('>').append(inline(trim.substring(n).trim()))
                       .append("</h").append(level).append('>');
                    continue;
                }
                if (trim.equals("---") || trim.equals("***")) { flush(); out.append("<hr>"); continue; }
                if (trim.startsWith("|") && trim.endsWith("|")) {
                    flush();
                    List<String> rows = new ArrayList<>();
                    rows.add(trim);
                    while (i + 1 < lines.length && lines[i + 1].trim().startsWith("|")) rows.add(lines[++i].trim());
                    table(rows);
                    continue;
                }
                if (trim.startsWith("> ") || trim.equals(">")) {
                    flush();
                    StringBuilder quote = new StringBuilder(trim.length() > 1 ? trim.substring(2) : "");
                    while (i + 1 < lines.length && lines[i + 1].trim().startsWith(">")) {
                        String q = lines[++i].trim();
                        quote.append('\n').append(q.length() > 1 ? q.substring(2) : "");
                    }
                    out.append("<blockquote>").append(new Render(0).run(quote.toString())).append("</blockquote>");
                    continue;
                }
                if (bullet(line) != null || numbered(line) != null) { flush(); i = list(lines, i); continue; }

                para.add(trim);
            }
            flush();
            while (!open.isEmpty()) out.append(open.pop());
            return out.toString();
        }

        // ------------------------------------------------------------ paragraphs

        private void flush() {
            if (para.isEmpty()) return;
            out.append("<p>").append(inline(String.join(" ", para))).append("</p>");
            para.clear();
        }

        // ------------------------------------------------------------------ lists

        private static String bullet(String line) {
            String t = line.stripLeading();
            return (t.startsWith("- ") || t.startsWith("* ")) ? t.substring(2) : null;
        }

        private static String numbered(String line) {
            String t = line.stripLeading();
            int dot = t.indexOf(". ");
            if (dot <= 0 || dot > 3) return null;
            for (int i = 0; i < dot; i++) if (!Character.isDigit(t.charAt(i))) return null;
            return t.substring(dot + 2);
        }

        /** One list, however deep, returning the last line it consumed. */
        private int list(String[] lines, int at) {
            boolean ordered = bullet(lines[at]) == null;
            int indent = indentOf(lines[at]);
            out.append(ordered ? "<ol>" : "<ul>");
            int i = at;
            while (i < lines.length) {
                String line = lines[i];
                String item = bullet(line) != null ? bullet(line) : numbered(line);
                if (item == null || indentOf(line) < indent) break;
                if (indentOf(line) > indent) { i = list(lines, i) + 1; continue; }

                StringBuilder body = new StringBuilder(item);
                // Wrapped lines belong to the item they are under.
                while (i + 1 < lines.length && !lines[i + 1].isBlank()
                        && bullet(lines[i + 1]) == null && numbered(lines[i + 1]) == null
                        && indentOf(lines[i + 1]) > indent) {
                    body.append(' ').append(lines[++i].trim());
                }
                out.append("<li>").append(inline(body.toString()));
                if (i + 1 < lines.length && !lines[i + 1].isBlank() && indentOf(lines[i + 1]) > indent
                        && (bullet(lines[i + 1]) != null || numbered(lines[i + 1]) != null)) {
                    i = list(lines, i + 1);
                }
                out.append("</li>");
                i++;
                if (i < lines.length && lines[i].isBlank()) {
                    // A blank line ends the list unless another item follows it.
                    int j = i;
                    while (j < lines.length && lines[j].isBlank()) j++;
                    if (j >= lines.length || (bullet(lines[j]) == null && numbered(lines[j]) == null)
                            || indentOf(lines[j]) < indent) break;
                    i = j;
                }
            }
            out.append(ordered ? "</ol>" : "</ul>");
            return i - 1;
        }

        /**
         * A fenced block, minus the indentation that only put it inside a
         * component. A sample nested in a Step is indented four spaces in the
         * source and should not be indented four spaces on the page.
         */
        private static String dedent(List<String> code) {
            int least = Integer.MAX_VALUE;
            for (String l : code) if (!l.isBlank()) least = Math.min(least, indentOf(l));
            if (least == Integer.MAX_VALUE) least = 0;
            StringBuilder sb = new StringBuilder();
            for (String l : code) sb.append(l.length() >= least ? l.substring(least) : l).append('\n');
            return sb.toString();
        }

        private static int indentOf(String line) {
            int n = 0;
            while (n < line.length() && line.charAt(n) == ' ') n++;
            return n;
        }

        // ------------------------------------------------------------------ tables

        private void table(List<String> rows) {
            out.append("<table>");
            for (int r = 0; r < rows.size(); r++) {
                String row = rows.get(r);
                if (row.replaceAll("[|\\-: ]", "").isEmpty()) continue;   // the rule under the head
                String[] cells = row.substring(1, row.length() - 1).split("\\|", -1);
                String tag = r == 0 ? "th" : "td";
                out.append("<tr>");
                for (String c : cells) {
                    out.append('<').append(tag).append('>').append(inline(c.trim()))
                       .append("</").append(tag).append('>');
                }
                out.append("</tr>");
            }
            out.append("</table>");
        }

        // -------------------------------------------------------------- components

        /** Attributes off an opening tag: {@code key="value"} and {@code key={value}}. */
        private static Map<String, String> attrs(String tag) {
            Map<String, String> out = new LinkedHashMap<>();
            var m = java.util.regex.Pattern
                    .compile("([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*(\"([^\"]*)\"|\\{([^}]*)\\})")
                    .matcher(tag);
            while (m.find()) out.put(m.group(1), m.group(3) != null ? m.group(3) : m.group(4));
            return out;
        }

        private static String name(String tag) {
            int i = 1;
            if (i < tag.length() && tag.charAt(i) == '/') i++;
            int j = i;
            while (j < tag.length() && (Character.isLetterOrDigit(tag.charAt(j)))) j++;
            return tag.substring(i, j);
        }

        /**
         * An opening component, if this line is one.
         *
         * <p>Only line-leading tags count, which is what keeps {@code List<String>}
         * and {@code StreamObserver<Pong>} out of here.
         */
        private boolean component(String tag) {
            String n = name(tag);
            if (n.isEmpty() || !Character.isUpperCase(n.charAt(0))) return false;
            if (!tag.endsWith(">")) return false;
            flush();
            var a = attrs(tag);
            boolean selfClosing = tag.endsWith("/>");

            String html = switch (n) {
                case "Note", "Info" -> callout("note", "Note");
                case "Tip", "Check" -> callout("tip", n.equals("Tip") ? "Tip" : "Check");
                case "Warning", "Danger" -> callout("warning", n.equals("Danger") ? "Danger" : "Warning");
                case "CardGroup", "Columns", "AccordionGroup" -> box("div", "cards");
                case "Card" -> card(a);
                case "Accordion" -> "<details><summary>" + esc(a.getOrDefault("title", "More"))
                        + "</summary>" + push("</details>");
                case "Steps" -> "<ol class=\"steps\">" + push("</ol>");
                case "Step" -> "<li>" + title(a) + push("</li>");
                case "Tabs" -> box("div", "tabs");
                case "Tab" -> "<section>" + title(a) + push("</section>");
                case "CodeGroup" -> box("div", "codegroup");
                case "Frame" -> "<figure>" + push("</figure>");
                case "ParamField", "ResponseField" -> field(a);
                // Anything else keeps its contents and loses its box. A missing
                // border is a smaller loss than a missing paragraph.
                default -> selfClosing ? "" : push("");
            };
            out.append(html);
            if (selfClosing && !open.isEmpty() && html.contains("<")) out.append(open.pop());
            return true;
        }

        private String callout(String kind, String label) {
            return "<div class=\"callout " + kind + "\"><span class=\"kind\">" + label + "</span>"
                    + push("</div>");
        }

        private String box(String tag, String cls) {
            return "<" + tag + " class=\"" + cls + "\">" + push("</" + tag + ">");
        }

        private String title(Map<String, String> a) {
            String t = a.get("title");
            return t == null ? "" : "<div class=\"t\">" + esc(t) + "</div>";
        }

        private String card(Map<String, String> a) {
            String href = a.get("href");
            String tag = href == null ? "div" : "a";
            String open = "<" + tag + " class=\"card\""
                    + (href == null ? "" : " href=\"" + esc(link(href, up)) + "\"") + ">";
            return open + title(a) + push("</" + tag + ">");
        }

        private String field(Map<String, String> a) {
            String n = a.getOrDefault("path", a.getOrDefault("body",
                       a.getOrDefault("query", a.getOrDefault("name", ""))));
            String ty = a.getOrDefault("type", "");
            boolean required = "true".equals(a.get("required"));
            return "<div class=\"field\"><span class=\"n\">" + esc(n) + "</span>"
                    + (ty.isEmpty() ? "" : "<span class=\"ty\">" + esc(ty) + "</span>")
                    + (required ? "<span class=\"req\">required</span>" : "")
                    + push("</div>");
        }

        private String push(String closing) { open.push(closing); return ""; }

        private void close(String tag) {
            flush();
            if (!open.isEmpty()) out.append(open.pop());
        }

        // ------------------------------------------------------------------ inline

        private String inline(String s) { return Manual.inline(s, up); }
    }

    // ------------------------------------------------------------------- inline

    /** Code first, so nothing inside backticks is touched by anything after it. */
    static String inline(String s, String up) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > 0) {
                    out.append("<code>").append(esc(s.substring(i + 1, end))).append("</code>");
                    i = end + 1;
                    continue;
                }
            }
            if (c == '[') {
                int close = s.indexOf(']', i);
                if (close > 0 && close + 1 < s.length() && s.charAt(close + 1) == '(') {
                    int end = s.indexOf(')', close);
                    if (end > 0) {
                        String text = s.substring(i + 1, close);
                        String href = s.substring(close + 2, end);
                        out.append("<a href=\"").append(esc(link(href, up))).append("\">")
                           .append(inline(text, up)).append("</a>");
                        i = end + 1;
                        continue;
                    }
                }
            }
            if (s.startsWith("**", i)) {
                int end = s.indexOf("**", i + 2);
                if (end > 0) {
                    out.append("<strong>").append(inline(s.substring(i + 2, end), up)).append("</strong>");
                    i = end + 2;
                    continue;
                }
            }
            if (c == '*' && i + 1 < s.length() && s.charAt(i + 1) != ' ') {
                int end = s.indexOf('*', i + 1);
                if (end > 0) {
                    out.append("<em>").append(inline(s.substring(i + 1, end), up)).append("</em>");
                    i = end + 1;
                    continue;
                }
            }
            out.append(switch (c) {
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '&' -> "&amp;";
                default -> String.valueOf(c);
            });
            i++;
        }
        return out.toString();
    }

    /**
     * An authored link, made to work from a page nested somewhere.
     *
     * <p>The pages are written with absolute paths ({@code /write/services}) for a
     * site served at a root. This manual is served under a prefix and read from a
     * page that may be two levels down, so those become relative.
     */
    static String link(String href, String up) {
        if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("#")) return href;
        if (href.startsWith("/")) return up + href.substring(1);
        return href;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** An image, a favicon, anything beside the pages. */
    public Path asset(String path) {
        if (path.contains("..")) return null;
        Path p = root.resolve(path).normalize();
        return p.startsWith(root) && Files.isRegularFile(p) ? p : null;
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }

    // -------------------------------------------------------------- on its own

    /**
     * The manual, as a process of its own.
     *
     * <p><b>Separate from the lab server on purpose, and this is the whole
     * reason:</b> the manual is where you look when something will not start. A
     * manual served by the thing that will not start is a manual you cannot read
     * at the one moment you need it — wrong Java, a port already taken, a compile
     * error in the very program that would have rendered the page. So it is its
     * own process on its own port, and it has one job: turn text into HTML. There
     * is nothing in it that a student's code can break.
     *
     * <p>And it is not the only way in. These pages are MDX — plain text with a
     * header — and they read perfectly well in the editor you are already sitting
     * in, with no server running at all. That is the floor beneath this.
     */
    /**
     * The manual, from a `main` of somebody's own — the same thing the command
     * line does, for an editor that has a run button and no terminal in it.
     */
    public static void open(String docs, int port) throws IOException {
        main(find(docs), port, "127.0.0.1");
    }

    /**
     * The manual, wherever it is relative to wherever this was started from.
     *
     * <p>A relative path is not good enough here. This is run by pressing the
     * arrow beside a `main` in an editor, and an editor's working directory is
     * its own business — it may be the project, the file's folder, or somewhere
     * else entirely. Getting "no manual at docs" from the one program that exists
     * for when things go wrong is the worst possible time for a path to be
     * ambiguous, so it looks upward until it finds one.
     */
    static Path find(String docs) {
        Path named = Path.of(docs);
        if (named.isAbsolute()) return named;
        Path here = Path.of("").toAbsolutePath();
        for (Path at = here; at != null; at = at.getParent()) {
            Path candidate = at.resolve(named);
            if (Files.isRegularFile(candidate.resolve("docs.json"))) return candidate;
        }
        // Nothing found: hand back what was asked for, so the message names the
        // path somebody wrote rather than one this method invented.
        return here.resolve(named);
    }

    /**
     * Why there is no manual here, and what to do about it.
     *
     * <p>Long, and deliberately so. This is the failure of the one program that
     * exists for when other things fail, reached by pressing an arrow in an
     * editor whose working directory is its own business — so the reader has no
     * command to inspect and no obvious reason why a program that is supposed to
     * explain everything cannot explain itself. Naming what was looked for, every
     * place it was looked, and the two ways to read the manual without this
     * program at all costs a few lines and removes a dead end.
     */
    static String notHere(Path asked) {
        var sb = new StringBuilder("\nThere is no manual here.\n\n");
        sb.append("  looked for   ").append(asked.getFileName()).append("/docs.json\n");
        sb.append("  started in   ").append(Path.of("").toAbsolutePath()).append('\n');
        sb.append("  and above    ");
        Path from = Path.of("").toAbsolutePath().getParent();
        if (from == null) sb.append("(nothing above it)\n");
        for (Path at = from; at != null; at = at.getParent())
            sb.append(at).append(at.getParent() == null ? "\n" : "\n               ");

        sb.append("""

                This serves the `docs/` folder of the project it is started in, and looks
                upward from wherever it was started. Nothing at or above that has one, so
                it is being run from outside the project — most often because the editor's
                run configuration has a working directory of its own.

                Either set that working directory to the project root, or say where the
                manual is:

                    Manual.open("/absolute/path/to/docs", 3000);

                You do not need this program to read the manual. `docs/` is plain text and
                opens in the editor you are already in, and the published site is up
                whatever is wrong here.
                """);
        return sb.toString();
    }

    public static int main(Path docs, int port, String host) throws IOException {

        Manual manual = new Manual(docs);
        if (!manual.present()) {
            java.lang.System.err.print(notHere(docs));
            return 2;
        }

        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (java.net.BindException e) {
            java.lang.System.out.printf("the manual is already open at http://localhost:%d%n", port);
            park();
            return 0;
        }
        http.createContext("/", x -> {
            String path = URLDecoder.decode(x.getRequestURI().getPath(), StandardCharsets.UTF_8)
                    .replaceAll("^/+", "");
            int dot = path.lastIndexOf('.');
            if (dot > path.lastIndexOf('/')) {
                Path asset = manual.asset(path);
                if (asset != null) {
                    reply(x, 200, TYPE.getOrDefault(path.substring(dot + 1), "application/octet-stream"),
                          Files.readAllBytes(asset));
                    return;
                }
            }
            byte[] html = manual.page(path);
            if (html == null) {
                reply(x, 404, "text/html; charset=utf-8",
                      ("<p>There is no page at <code>" + esc(path) + "</code>. "
                       + "<a href=\"/\">Start at the beginning.</a>").getBytes(StandardCharsets.UTF_8));
                return;
            }
            reply(x, 200, "text/html; charset=utf-8", html);
        });
        http.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "losim-manual");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        java.lang.System.out.printf("the manual is at http://localhost:%d%n", port);
        park();
        return 0;
    }

    private static final Map<String, String> TYPE = Map.of(
            "svg", "image/svg+xml", "png", "image/png", "jpg", "image/jpeg",
            "gif", "image/gif", "ico", "image/x-icon", "json", "application/json",
            "css", "text/css; charset=utf-8", "js", "text/javascript; charset=utf-8");

    private static void reply(HttpExchange x, int code, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(code, body.length);
        try (OutputStream out = x.getResponseBody()) { out.write(body); }
    }

    private static void park() {
        try { Thread.currentThread().join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
