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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import losim.trace.Json;
import losim.trace.JsonReader;

/**
 * The one thing that runs, and keeps running.
 *
 * <p>A student on this course has a code editor and a browser, and that is all
 * they should need. Everything a command line was doing — build this, run that,
 * bill the trace, serve the viewer, find yesterday's run — happens here instead,
 * behind a button. The devcontainer starts it; nobody types anything.
 *
 * <p>Three things are served from one port, deliberately:
 *
 * <ul>
 *   <li><b>the viewer</b>, as the static files it was exported to. No npm, ever
 *       (D10) — and now no Python either, because the JDK the lab already needs
 *       can serve a directory perfectly well;
 *   <li><b>the runs</b>, listed live from {@code build/runs} rather than from a
 *       manifest somebody has to remember to rewrite. A run appears in the picker
 *       because it is on disk, which is the only fact that cannot go stale;
 *   <li><b>the systems</b>, each with a way to run it and a log to watch while it
 *       does.
 * </ul>
 *
 * <p><b>What is deliberately not here:</b> anything that writes a student's code,
 * and any way to run something that is not a system in this project. The server
 * compiles what is in the folder and runs the scenario beside it; it is a button
 * for the toolchain, not a shell with a web page in front of it.
 */
public final class Serve {

    private final Lab lab;
    private final Path site;
    private final Path runs;

    /** One at a time: two builds racing would fight over the same output tree. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "losim-run");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger jobs = new AtomicInteger();
    private volatile Job current;

    private Serve(Lab lab, Path site, Path runs) {
        this.lab = lab;
        this.site = site;
        this.runs = runs;
    }

    /**
     * Where the exported viewer is, in whichever kind of project this is.
     *
     * <p>A lab carries it at {@code viewer/}, committed, because a student must
     * never need npm. The simulator's own repository builds it into
     * {@code build/viewer}. Looking for both means neither has to say so.
     */
    public static Path siteIn(Path base, String named) {
        if (named != null) return base.resolve(named).normalize();
        Path lab = base.resolve("viewer");
        if (Files.isRegularFile(lab.resolve("index.html"))) return lab;
        return base.resolve("build/viewer").normalize();
    }

    /**
     * One run, and everything anybody watching it needs to know.
     *
     * <p>The log accumulates in memory rather than being streamed to a socket,
     * because the page that started a run is not necessarily the page reading it:
     * a Codespace reconnects, a browser is refreshed, and the output of the run
     * that is still going has to still be there.
     */
    private static final class Job {
        final int id;
        final String task;
        final String world;
        final StringBuilder log = new StringBuilder();
        /**
         * How much has been dropped off the front, ever.
         *
         * <p>The cursor a browser holds counts from the beginning of the run, not
         * from the beginning of what is still in memory. Without this the two
         * disagree the moment the log is trimmed: the page asks for a position
         * past the end of a now-shorter buffer, is told there is nothing, and
         * stops showing the rest of the run.
         */
        long dropped;
        volatile boolean done;
        volatile int code = -1;

        Job(int id, String task, String world) { this.id = id; this.task = task; this.world = world; }

        synchronized void say(String s) {
            log.append(s);
            // A runaway program must not become a runaway process. Keeping the
            // tail is right: the end of a log is where the failure is.
            if (log.length() > 400_000) {
                int cut = log.length() - 300_000;
                log.delete(0, cut);
                dropped += cut;
            }
        }

        /** What was said after {@code at}, and where to ask from next. */
        synchronized Tail from(long at) {
            long end = dropped + log.length();
            long start = Math.max(at - dropped, 0);
            return start >= log.length()
                    ? new Tail("", end)
                    : new Tail(log.substring((int) start), end);
        }
    }

    /**
     * A slice of a run's output, with the cursor that follows it.
     *
     * <p>The two travel together because they have to be read under one lock: a
     * length taken after the text was copied would skip whatever was said in
     * between.
     */
    private record Tail(String text, long next) {}

    // --------------------------------------------------------------------- boot

    /**
     * @param warm whether to compile every system that has code in it before
     *             anybody asks. True for the lab, where a student is about to
     *             press something; false when the viewer is being opened on runs
     *             that have just been made, because there is nothing to get ready.
     */
    public static int main(String root, String site, String runs, int port, String host,
                           boolean open, boolean warm) throws IOException {
        Path base = Path.of(root).toAbsolutePath().normalize();
        // One runs directory, given to both: the picker lists it and the run
        // button writes into it, and `--runs` has to move both or neither.
        Path where = runs == null ? base.resolve(Lab.RUNS)
                                  : Path.of(runs).toAbsolutePath().normalize();
        Serve s = new Serve(new Lab(base, base.resolve("lib"), where), siteIn(base, site), where);

        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (java.net.BindException e) {
            // Re-attaching to a container starts this again while the first one
            // is still up. That is not a failure and must not read like one:
            // the window the student is looking at is already served.
            java.lang.System.out.printf("losim is already running on http://localhost:%d%n", port);
            try { Thread.currentThread().join(); }
            catch (InterruptedException i) { Thread.currentThread().interrupt(); }
            return 0;
        }
        http.createContext("/api/tasks", s::tasks);
        http.createContext("/api/run", s::start);
        http.createContext("/api/log", s::log);
        http.createContext("/traces/", s::trace);
        http.createContext("/", s::asset);
        // Requests are cheap and a run is not: the run happens on `worker`, and
        // these threads only ever start it and read what it has said so far.
        http.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "losim-http");
            t.setDaemon(true);
            return t;
        }));
        http.start();

        if (warm) s.warm();

        java.lang.System.out.printf("losim is running on http://localhost:%d%n", port);
        if (!s.lab.isLab()) {
            java.lang.System.out.println("  systems  none — " + base + " has no lib/losim.jar,");
            java.lang.System.out.println("           so it is not a lab. Point --root at one.");
        } else {
            java.lang.System.out.printf("  systems  %d in %s%n", s.lab.tasks().size(), base);
        }
        java.lang.System.out.printf("  runs     %s%n", s.runs);
        java.lang.System.out.println("  leave this running; press the arrow beside a system to run it.");
        if (open) browse("http://localhost:" + port + "/");

        // The point of this process is to still be here later.
        try { Thread.currentThread().join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return 0;
    }

    // ------------------------------------------------------------- the other two

    /**
     * Compile what already has code in it, before anybody presses anything.
     *
     * <p>The first thing a student does is press the arrow beside the first
     * system, and the first build of a JVM's worth of gRPC takes long enough to
     * read as broken. Doing it while they are still finding the window costs
     * nothing and removes the worst first impression this course can make.
     *
     * <p>It runs on the same single worker as a real run, so a student who is
     * quicker than the warm-up queues behind it rather than racing it.
     *
     * <p><b>Compiling, and nothing further.</b> Running each system here instead
     * would overwrite every trace on disk with a fresh one every time the server
     * starts — so a student who reopens their Codespace the next morning would
     * find yesterday's run silently replaced — and one chaos scenario would hold
     * the single worker for minutes while the page they press reports nothing
     * running.
     */
    private void warm() {
        worker.submit(() -> {
            for (Lab.Task t : lab.tasks()) {
                if (!t.started()) continue;
                try { lab.compile(t, x -> { }); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                catch (Exception ignored) {
                    // A system that does not build yet is the starting position,
                    // not a problem with the container.
                }
            }
        });
    }

    // ------------------------------------------------------------------ the api

    private void tasks(HttpExchange x) throws IOException {
        List<Object> out = new ArrayList<>();
        for (Lab.Task t : lab.tasks()) {
            Path trace = lab.trace(t, null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.id());
            row.put("started", t.started());
            row.put("distributed", t.distributed());
            row.put("files", t.sources().size());
            row.put("schema", !t.protos().isEmpty());
            // Every world this system can be put in, so a variant is a second
            // button beside the first rather than a file nobody notices.
            row.put("scenarios", t.worldNames());
            if (trace != null && Files.exists(trace)) row.put("trace", "traces/" + trace.getFileName());
            out.add(row);
        }
        Job j = current;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tasks", out);
        body.put("busy", j != null && !j.done ? j.task : null);
        send(x, 200, "application/json", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Start a run, and answer immediately with where to read it.
     *
     * <p>Answering before the run finishes is the whole design: a build takes
     * seconds and a fleet under chaos takes longer, and a page that waits for it
     * is a page that looks broken.
     */
    private void start(HttpExchange x) throws IOException {
        if (!"POST".equals(x.getRequestMethod())) { send(x, 405, "text/plain", "POST".getBytes()); return; }
        Map<String, Object> body = readJson(x);
        String id = String.valueOf(body.getOrDefault("task", ""));
        Object named = body.get("scenario");
        String world = named == null ? null : String.valueOf(named);
        Lab.Task t = lab.task(id);
        if (t == null) { fail(x, 404, "There is no system called " + id + " in this project."); return; }

        Job running = current;
        if (running != null && !running.done) {
            fail(x, 409, running.task + " is still running. It will finish, or you can wait it out.");
            return;
        }
        Job job = new Job(jobs.incrementAndGet(), id, world);
        current = job;
        worker.submit(() -> {
            job.say("── " + id + (world == null || world.isBlank() ? "" : "  " + world) + " ──\n");
            try {
                job.code = lab.run(t, world, job::say);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                job.say("\nstopped.\n");
            } catch (Exception e) {
                // losim's own failure, not the student's, and it must say which.
                job.say("\nlosim could not run this: " + e + "\n");
                job.code = 3;
            } finally {
                job.done = true;
            }
        });
        send(x, 200, "application/json",
                Json.write(Map.of("job", job.id, "task", id)).getBytes(StandardCharsets.UTF_8));
    }

    /** What the current run has said since byte {@code from}. */
    private void log(HttpExchange x) throws IOException {
        Job j = current;
        Map<String, Object> body = new LinkedHashMap<>();
        if (j == null) {
            body.put("text", "");
            body.put("next", 0);
            body.put("done", true);
        } else {
            Tail tail = j.from((long) number(query(x).getOrDefault("from", "0")));
            body.put("job", j.id);
            body.put("task", j.task);
            body.put("text", tail.text());
            body.put("next", tail.next());
            body.put("done", j.done);
            body.put("ok", j.done && j.code == 0);
            if (j.done) {
                Lab.Task t = lab.task(j.task);
                Path trace = t == null ? null : lab.trace(t, j.world);
                if (trace != null && Files.exists(trace))
                    body.put("trace", "traces/" + trace.getFileName());
            }
        }
        send(x, 200, "application/json", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------- traces

    /**
     * The runs on disk, and the index the picker reads.
     *
     * <p>Built on every request rather than written out once. A student runs a
     * system, switches to the browser and expects it to be there; a manifest
     * written by whoever last swept a directory is a promise nobody keeps.
     */
    private void trace(HttpExchange x) throws IOException {
        String name = x.getRequestURI().getPath().substring("/traces/".length());
        if (name.equals("index.json")) { send(x, 200, "application/json", index()); return; }
        if (name.isEmpty() || name.contains("/") || name.contains("..")) { fail(x, 404, "no such run"); return; }

        Path p = runs.resolve(name);
        if (!Files.isRegularFile(p)) {
            // The viewer asks for a bill beside every trace and most runs have
            // one; a missing bill is an absence, not an error, and it says so.
            fail(x, 404, "no such run: " + name);
            return;
        }
        send(x, 200, "application/json", Files.readAllBytes(p));
    }

    /**
     * Cached by (size, mtime): re-reading every trace on every poll is not free.
     *
     * <p>Concurrent because the picker polls it and eight HTTP threads answer.
     * Two polls landing together on a trace a run had just rewritten both missed
     * the cache and both rebuilt it, and a plain HashMap structurally modified
     * from two threads at once loses entries or throws out of {@code removeIf}.
     */
    private final Map<String, Map<String, Object>> summaries = new java.util.concurrent.ConcurrentHashMap<>();

    private byte[] index() {
        List<Object> out = new ArrayList<>();
        if (Files.isDirectory(runs)) {
            try (var s = Files.list(runs)) {
                List<Path> files = s.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> !p.getFileName().toString().endsWith(".bill.json"))
                        .sorted().toList();
                for (Path p : files) out.add(summary(p));
            } catch (IOException ignored) { /* an unreadable runs dir is an empty picker */ }
        }
        return Json.write(Map.of("runs", out)).getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, Object> summary(Path p) {
        String name = p.getFileName().toString().replaceAll("\\.json$", "");
        String key;
        try {
            key = name + ":" + Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            key = name;
        }
        Map<String, Object> hit = summaries.get(key);
        if (hit != null) return hit;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("href", "traces/" + p.getFileName());
        row.put("from", "yours");
        try {
            Object parsed = JsonReader.read(Files.readString(p));
            if (parsed instanceof Map<?, ?> m) {
                if (m.get("meta") instanceof Map<?, ?> meta) {
                    Object d = meta.get("durationRefMs");
                    if (d instanceof Number n) row.put("durationRefMs", n.doubleValue());
                    Object job = meta.get("job");
                    if (job != null) row.put("job", String.valueOf(job));
                }
                if (m.get("machines") instanceof List<?> l) row.put("machines", l.size());
            }
        } catch (Exception ignored) {
            // A half-written trace is what a run in progress looks like. It will
            // be readable in a moment; until then it is a name in the list.
        }
        summaries.keySet().removeIf(k -> k.startsWith(name + ":"));
        summaries.put(key, row);
        return row;
    }

    // ------------------------------------------------------------------- static

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("map", "application/json"));

    private void asset(HttpExchange x) throws IOException {
        // Already decoded: `URI.getPath()` has done the percent-escapes, and
        // running URLDecoder over the result decodes them a second time — under
        // form rules, which turn a `+` in a file name into a space. `/a%2Bb`
        // came back as "there is no page at a b".
        String path = x.getRequestURI().getPath();
        Path p = site.resolve(path.substring(1)).normalize();
        // The one rule that matters here: nothing outside the exported site.
        if (!p.startsWith(site)) { fail(x, 403, "not yours to read"); return; }
        if (Files.isDirectory(p)) p = p.resolve("index.html");
        if (!Files.isRegularFile(p)) {
            Path html = p.resolveSibling(p.getFileName() + ".html");
            if (Files.isRegularFile(html)) p = html;
            else {
                if (!Files.isDirectory(site)) {
                    fail(x, 404, "The viewer has not been built into " + site + ".");
                } else {
                    fail(x, 404, "no such page");
                }
                return;
            }
        }
        String name = p.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        send(x, 200, TYPES.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(p));
    }

    // ------------------------------------------------------------------- plumbing

    private static void send(HttpExchange x, int code, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        // Every answer here is about what is on disk right now, and a cached
        // "there are no runs yet" is exactly the wrong thing to keep.
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(code, body.length);
        try (OutputStream out = x.getResponseBody()) { out.write(body); }
    }

    /** A failure a person reads, not a status code they have to look up. */
    private static void fail(HttpExchange x, int code, String why) throws IOException {
        send(x, code, "application/json",
                Json.write(Map.of("error", why)).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> readJson(HttpExchange x) throws IOException {
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) return Map.of();
        try { return JsonReader.readObject(body); }
        catch (Exception e) { return Map.of(); }
    }

    private static Map<String, String> query(HttpExchange x) {
        Map<String, String> out = new LinkedHashMap<>();
        String q = x.getRequestURI().getRawQuery();
        if (q == null) return out;
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            out.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    /**
     * Best effort, and never an error.
     *
     * <p>Inside a container there is no browser to open and the port is forwarded
     * to one outside, which does the opening itself — so this is skipped there
     * rather than failing there.
     */
    static void browse(String url) {
        if (Main.contained()) return;
        String os = java.lang.System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        List<String> argv = os.contains("mac") ? List.of("open", url)
                : os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                : List.of("xdg-open", url);
        try { new ProcessBuilder(argv).start(); }
        catch (IOException ignored) { /* the URL is printed either way */ }
    }

    private static double number(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }
}
