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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import losim.res.InstanceCatalog;
import losim.res.InstanceSpec;
import losim.res.Regions;
import losim.scenario.Loader;
import losim.scenario.Yaml;
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

    private final AtomicInteger runs_ = new AtomicInteger();
    private volatile Run current;

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
    private static final class Run {
        final int id;
        final String scenario;
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

        Run(int id, String scenario) {
            this.id = id; this.scenario = scenario;
        }

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
        http.createContext("/api/scenarios", safe(s::scenarios));
        http.createContext("/api/classes", safe(s::classes));
        http.createContext("/api/scenario", safe(s::scenario));
        http.createContext("/api/run", safe(s::start));
        http.createContext("/api/log", safe(s::log));
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
            java.lang.System.out.printf("  scenarios  %d in %s%n", s.lab.scenarios().size(), base);
        }
        java.lang.System.out.printf("  runs     %s%n", s.runs);
        java.lang.System.out.println("  leave this running; press the arrow beside a scenario to run it.");
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
            if (!lab.code().started()) return;
            try { lab.compile(x -> { }); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception ignored) {
                // A lab that does not build yet is the starting position, not a
                // problem with the container.
            }
        });
    }

    // ------------------------------------------------------------------ the api

    private void scenarios(HttpExchange x) throws IOException {
        List<Object> out = new ArrayList<>();
        for (Path sc : lab.scenarios()) {
            String name = sc.getFileName().toString();
            Path trace = lab.trace(name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("path", lab.root().relativize(sc).toString().replace('\\', '/'));
            if (trace != null && Files.exists(trace)) row.put("trace", "traces/" + trace.getFileName());
            out.add(row);
        }
        Lab.Code code = lab.code();
        Run r = current;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scenarios", out);
        body.put("started", code.started());
        body.put("files", code.sources().size());
        body.put("schema", !code.protos().isEmpty());
        body.put("busy", r != null && !r.done ? r.scenario : null);
        send(x, 200, "application/json", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * What the lab's code offers a machine, and what a machine can be.
     *
     * <p>The two halves of authoring a scenario. A student places <b>classes</b>
     * on <b>machines</b>: the classes are read off their own compiled bytecode by
     * {@link Palette}, and the machines are the instance catalogue and the regions
     * losim already prices. Neither has ever been discoverable without reading
     * losim's source, which is why scenarios have been written by copying one.
     *
     * <p>It compiles when it has to and not otherwise. A page that regenerated
     * protobuf every time somebody opened it would take five seconds to open, and
     * the answer would be the same one.
     */
    private void classes(HttpExchange x) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scenarios", lab.scenarioNames());
        body.put("instances", instances());
        body.put("regions", regions());

        Path classes = lab.classes();
        if (!lab.compiled()) {
            StringBuilder log = new StringBuilder();
            try {
                // On the same single worker as a real run: a compile racing a run
                // over one `classes` directory is a build that reports classes
                // that were deleted underneath it.
                classes = worker.submit(() -> lab.compile(log::append)).get();
            } catch (java.util.concurrent.ExecutionException e) {
                classes = null;
                log.append(e.getCause() == null ? String.valueOf(e) : String.valueOf(e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                classes = null;
            }
            if (classes == null) {
                body.put("compiled", false);
                body.put("log", log.toString());
                send(x, 200, "application/json", Json.write(body).getBytes(StandardCharsets.UTF_8));
                return;
            }
        }

        Palette.Offer offer = Palette.of(classes, lab, lab.code().sources());
        List<Object> services = new ArrayList<>();
        for (Palette.Service sv : offer.services()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cls", sv.cls());
            row.put("service", sv.service());
            row.put("qualified", sv.qualified());
            List<Object> methods = new ArrayList<>();
            for (Palette.Method m : sv.methods()) {
                methods.add(new LinkedHashMap<>(Map.of("name", m.name(),
                                                       "idempotent", m.idempotent())));
            }
            row.put("methods", methods);
            if (sv.source() != null) row.put("source", sv.source());
            services.add(row);
        }
        body.put("compiled", true);
        body.put("jobs", offer.jobs());
        body.put("services", services);
        body.put("other", offer.other());
        send(x, 200, "application/json", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private List<Object> instances() {
        List<Object> out = new ArrayList<>();
        for (var e : InstanceCatalog.all().entrySet()) {
            InstanceSpec i = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", i.name());
            row.put("family", i.family());
            row.put("vcpu", i.vcpu());
            row.put("memoryMb", i.memoryMb());
            row.put("storageGb", i.storageGb());
            row.put("burstable", i.burstable());
            row.put("onDemandPerHour", i.onDemandPerHour());
            out.add(row);
        }
        return out;
    }

    /**
     * Every place a machine could be, and what the zones there are called.
     *
     * <p>Spelled the way {@link Regions} parses them, which is not the same in
     * both clouds — {@code eu-central-1a} but {@code switzerlandnorth-1}. A
     * console that composed those itself would compose one of them wrongly, and
     * the scenario would load with a zone that is silently its own region.
     */
    private List<Object> regions() {
        List<Object> out = new ArrayList<>();
        for (var e : Regions.all().entrySet()) {
            Regions.Region r = e.getValue();
            List<String> zones = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                zones.add("azure".equals(r.provider())
                        ? e.getKey() + "-" + (i + 1)
                        : e.getKey() + (char) ('a' + i));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.getKey());
            row.put("provider", r.provider());
            row.put("continent", r.continent());
            row.put("where", r.where());
            row.put("zones", zones);
            out.add(row);
        }
        return out;
    }

    /**
     * Write a scenario, having first refused to write a broken one.
     *
     * <p>It is loaded before it is saved, by the same {@link Loader} a run uses,
     * so what lands on disk is a file that will start. The alternative is a
     * console that writes whatever it composed and a student who finds out it was
     * wrong from a stack trace two clicks later — and by then the file with the
     * mistake in it is already theirs to fix.
     *
     * <p><b>It only ever writes inside the lab's scenarios folder.</b> The name is
     * a file name and nothing else: no separators, no dots that walk anywhere, and
     * the resolved path is checked against that folder before a byte is written.
     * This is a web page writing into somebody's project.
     */
    private void scenario(HttpExchange x) throws IOException {
        if (!"POST".equals(x.getRequestMethod())) { send(x, 405, "text/plain", "POST".getBytes()); return; }
        Map<String, Object> body = readJson(x);
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        String text = String.valueOf(body.getOrDefault("yaml", ""));

        if (!name.endsWith(".yaml")) name = name + ".yaml";
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.yaml")) {
            fail(x, 400, "'" + name + "' is not a scenario name. Letters, digits, dot, dash and"
                    + " underscore, and it is a file name rather than a path.");
            return;
        }

        try {
            Loader.of(Yaml.parse(name, text));
        } catch (RuntimeException e) {
            // The loader's own words, with the line it was written on. Nothing
            // here can say it better, and re-wording it would lose the line.
            fail(x, 400, e.getMessage() == null ? String.valueOf(e) : e.getMessage());
            return;
        }

        Path into = lab.root().resolve(Lab.SCENARIOS);
        Path file = into.resolve(name).normalize();
        if (!file.startsWith(into.toAbsolutePath().normalize())) {
            fail(x, 400, "a scenario belongs in the lab's scenarios folder");
            return;
        }
        Files.createDirectories(file.getParent());
        boolean existed = Files.exists(file);
        Files.writeString(file, text);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", name);
        out.put("path", lab.root().relativize(file).toString().replace('\\', '/'));
        out.put("replaced", existed);
        send(x, 200, "application/json", Json.write(out).getBytes(StandardCharsets.UTF_8));
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
        Object named = body.get("scenario");
        String scenario = named == null ? null : String.valueOf(named);
        if (lab.scenario(scenario) == null) {
            fail(x, 404, "There is no scenario called " + scenario + " in this lab.");
            return;
        }

        Run running = current;
        if (running != null && !running.done) {
            fail(x, 409, running.scenario + " is still running. It will finish, or you can wait it out.");
            return;
        }
        Run run = new Run(runs_.incrementAndGet(), scenario);
        current = run;
        worker.submit(() -> {
            run.say("── " + scenario + " ──\n");
            try {
                run.code = lab.run(scenario, run::say);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                run.say("\nstopped.\n");
            } catch (Exception e) {
                // losim's own failure, not the student's, and it must say which.
                run.say("\nlosim could not run this: " + e + "\n");
                run.code = 3;
            } finally {
                run.done = true;
            }
        });
        send(x, 200, "application/json",
                Json.write(Map.of("run", run.id, "scenario", scenario)).getBytes(StandardCharsets.UTF_8));
    }

    /** What the current run has said since byte {@code from}. */
    private void log(HttpExchange x) throws IOException {
        Run r = current;
        Map<String, Object> body = new LinkedHashMap<>();
        if (r == null) {
            body.put("text", "");
            body.put("next", 0);
            body.put("done", true);
        } else {
            Tail tail = r.from((long) number(query(x).getOrDefault("from", "0")));
            body.put("run", r.id);
            body.put("scenario", r.scenario);
            body.put("text", tail.text());
            body.put("next", tail.next());
            body.put("done", r.done);
            body.put("ok", r.done && r.code == 0);
            if (r.done) {
                Path trace = lab.trace(r.scenario);
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
                        // The index is in the directory it indexes, and it is not a run.
                        .filter(p -> !p.getFileName().toString().equals("index.json"))
                        .sorted().toList();
                Map<String, String> origins = origins(runs);
                for (Path p : files) out.add(summary(p, origins));
            } catch (IOException ignored) { /* an unreadable runs dir is an empty picker */ }
        }
        return Json.write(Map.of("runs", out)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Whose run each trace is, when somebody has written it down.
     *
     * `viewer/traces.sh` leaves a `.origins` file beside the traces it sweeps,
     * because a student's own first run must not appear as one line among a
     * hundred worked examples. Absent — the ordinary case, a lab serving the
     * runs it just made — everything here is yours, which it is.
     */
    private Map<String, String> origins(Path runs) {
        Map<String, String> out = new LinkedHashMap<>();
        Path marks = runs.resolve(".origins");
        if (!Files.isReadable(marks)) return out;
        try {
            for (String line : Files.readAllLines(marks)) {
                int tab = line.indexOf('\t');
                if (tab > 0) out.put(line.substring(0, tab), line.substring(tab + 1));
            }
        } catch (IOException ignored) { /* a list nobody can read is no list */ }
        return out;
    }

    /**
     * One line of the picker, and one card of the gallery.
     *
     * More than a name, because the page this feeds is a gallery rather than a
     * dropdown: what a run cost, how many machines it had and how far apart they
     * were are the things somebody chooses between runs *on*, and a card that
     * cannot say them is a card nobody can choose from. All of it is copied out
     * of the trace and the bill beside it — nothing here is computed, because a
     * second place that prices a run is a second accountant.
     */
    private Map<String, Object> summary(Path p, Map<String, String> origins) {
        String name = p.getFileName().toString().replaceAll("\\.json$", "");
        Path billed = p.resolveSibling(name + ".bill.json");
        String key;
        try {
            key = name + ":" + Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis()
                    + ":" + (Files.isReadable(billed) ? Files.getLastModifiedTime(billed).toMillis() : 0);
        } catch (IOException e) {
            key = name;
        }
        Map<String, Object> hit = summaries.get(key);
        if (hit != null) return hit;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("href", "traces/" + p.getFileName());
        row.put("from", origins.getOrDefault(name, "yours"));
        try {
            Object parsed = JsonReader.read(Files.readString(p));
            if (parsed instanceof Map<?, ?> m) {
                if (m.get("meta") instanceof Map<?, ?> meta) {
                    Object d = meta.get("durationRefMs");
                    if (d instanceof Number n) row.put("durationRefMs", n.doubleValue());
                    Object job = meta.get("job");
                    if (job != null) row.put("job", String.valueOf(job));
                    Object scenario = meta.get("scenario");
                    if (scenario != null) row.put("scenario", String.valueOf(scenario));
                    if (Boolean.FALSE.equals(meta.get("completed"))) row.put("completed", false);
                }
                if (m.get("machines") instanceof List<?> l) {
                    row.put("machines", l.size());
                    List<String> zones = new ArrayList<>();
                    for (Object o : l) {
                        if (o instanceof Map<?, ?> mm && mm.get("zone") instanceof String z
                                && !z.isEmpty() && !zones.contains(z)) {
                            zones.add(z);
                        }
                    }
                    Collections.sort(zones);
                    if (!zones.isEmpty()) row.put("zones", zones);
                }
            }
        } catch (Exception ignored) {
            // A half-written trace is what a run in progress looks like. It will
            // be readable in a moment; until then it is a name in the list.
        }
        try {
            if (Files.isReadable(billed)
                    && JsonReader.read(Files.readString(billed)) instanceof Map<?, ?> b
                    && b.get("observed") instanceof Map<?, ?> o) {
                if (o.get("cost") instanceof Number c) row.put("cost", c.doubleValue());
                if (o.get("currency") instanceof String c) row.put("currency", c);
                if (o.get("buckets") instanceof Map<?, ?> bk) row.put("buckets", bk);
            }
        } catch (Exception ignored) {
            // No bill, or one being rewritten. The money is simply absent, which
            // is the right failure: the viewer will not invent prices of its own.
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

    /**
     * An unexpected failure is still an answer.
     *
     * <p>A handler that throws leaves the browser waiting on a socket nobody will
     * ever write to, and the page it is behind simply never loads — which reads
     * as the whole server being wedged rather than as one endpoint being wrong.
     * The message is losim's own, because a 500 with nothing in it is a thing
     * somebody has to go and find the log for.
     */
    private static com.sun.net.httpserver.HttpHandler safe(com.sun.net.httpserver.HttpHandler h) {
        return x -> {
            try {
                h.handle(x);
            } catch (Throwable e) {
                if (x.getResponseCode() != -1) return;   // already answered; the rest is the socket's
                try { fail(x, 500, "losim could not answer that: " + e); }
                catch (IOException ignored) { /* the client has gone */ }
            }
        };
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
