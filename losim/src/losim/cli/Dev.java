package losim.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The half of the CLI that only losim's own repository has any use for.
 *
 * <p>Everything here used to be a shell script — about seven hundred lines of
 * bash whose only consumer was CI, and which had already produced one bug of its
 * own: {@code GROUPS} is a read-only bash variable holding the user's group ids,
 * so a script that assigned to it silently ran twenty rounds on a Mac and asked
 * for a thousand on a runner. Nothing tested any of it, because nothing tests a
 * build script.
 *
 * <p><b>It is hidden, and it refuses rather than misfires.</b> A lab has no
 * {@code losim/src} and no {@code vendor/jars}, so every verb here would run
 * against directories that are not there and fail somewhere deep. It is not in
 * the usage text either: a student typing {@code losim} is being told what they
 * can do, and eight maintainer commands in that list are eight things to wonder
 * whether they should have run.
 *
 * <p><b>Nothing here builds the jar.</b> The scripts each began with
 * {@code gradle -q jar}, which was fine for a script and is not fine here: this
 * code is running <i>out of</i> {@code build/losim.jar}, and a Gradle jar task
 * truncates its output file in place. A class loaded lazily after that point
 * reads a jar that is being rewritten under it. Building is the launcher's job —
 * {@code ./losim} runs Gradle and only then starts a JVM — so by the time any of
 * this executes, the jar is both current and finished.
 */
public final class Dev {

    /** {@code :} or {@code ;}, which every classpath here is joined with. */
    private static final String SEP = java.io.File.pathSeparator;

    private final Path root;

    private Dev(Path root) { this.root = root.toAbsolutePath().normalize(); }

    /**
     * Whether this directory is losim's own source tree.
     *
     * <p>Both, not either: {@code vendor/jars} alone is a lab that vendored its
     * dependencies, and {@code losim/src} alone is somebody reading the source.
     */
    public static boolean here(Path root) {
        return Files.isDirectory(root.resolve("losim/src"))
                && Files.isDirectory(root.resolve("vendor/jars"));
    }

    public static int main(String[] args) throws Exception {
        Path root = Path.of(Main.option(args, "--root", "."));
        if (!here(root)) {
            System.err.println("""
                `losim dev` is for losim's own repository, and this is not one: it has no
                losim/src and no vendor/jars. Nothing a lab does needs any of these verbs.""");
            return 2;
        }
        Dev dev = new Dev(root);
        String verb = args.length > 1 ? args[1] : "";
        List<String> rest = new ArrayList<>();
        for (int i = 2; i < args.length; i++) if (!args[i].startsWith("--")) rest.add(args[i]);
        return switch (verb) {
            case "test"   -> dev.test(rest);
            case "suite"  -> dev.suite(rest);
            case "viewer" -> dev.viewer(args);
            case "docs"   -> dev.docs(args);
            case "vendor" -> dev.vendor();
            case "proto"  -> dev.proto();
            default -> {
                System.err.println("""
                    usage: losim dev test  [Phase1 Phase2 …]   losim's own acceptance criteria
                           losim dev suite [t1 t2 … bill]      the reference suite, end to end

                           losim dev viewer traces [dir] [--suite] [--gallery] [--all]
                           losim dev viewer serve  [dir] [--port 8000]   sweep, then serve
                           losim dev viewer build                        npx next build
                           losim dev viewer dev    [--port 8000]         the Next dev server
                           losim dev viewer check                        the node checks

                           losim dev docs check [--rules]   does the manual give an assignment away

                           losim dev vendor                 fetch the toolchain into vendor/
                           losim dev proto                  regenerate losim/src/losim/pb from
                                                            losim/proto, and say if it changed

                    All of them assume build/losim.jar is current. `bin/losim` makes sure of
                    that before it starts a JVM; if you are calling java directly, run
                    `gradle jar` first.""");
                yield 2;
            }
        };
    }

    // ------------------------------------------------------------------- the two suites

    /**
     * losim's own checks: five phase suites, then a handler alone in JUnit.
     *
     * <p>Every suite is run even after one fails, and the exit code is the
     * disjunction. A run that stopped at the first failure would say nothing
     * about the other four, and the question being asked is what is broken
     * rather than whether anything is.
     */
    private int test(List<String> named) throws Exception {
        String cp = vendorJars() + jar();
        wipe(root.resolve("build/test-gen"));
        wipe(root.resolve("build/test-classes"));
        // The plan cache is keyed on the code, but the phase suites fit plans of
        // their own and a stale one is a test measuring the last commit.
        wipe(root.resolve("build/.losim-plans"));

        if (protoc(root.resolve("losim/test/proto"), root.resolve("build/test-gen")) != 0) return 1;
        if (javac(cp, root.resolve("build/test-classes"),
                  sources(root.resolve("build/test-gen"), root.resolve("losim/test/src"))) != 0) return 1;

        List<String> suites = named.isEmpty()
                ? List.of("Phase1", "Phase2", "Phase3", "Phase4", "Debugger") : named;
        boolean fail = false;
        String lab = cp + SEP + root.resolve("build/test-classes");
        for (String s : suites) fail |= exec(List.of(java(), "-Xmx3g", "-cp", lab, s)) != 0;

        // A handler, on its own, in plain JUnit, with nothing simulating
        // anything. On a classpath of its own: JUnit belongs to a test and must
        // never be reachable from a lab, where an import of it would compile and
        // then teach that a handler is something you assert about in isolation.
        String tcp = jars(root.resolve("vendor/test-jars"));
        Path junitClasses = root.resolve("build/junit-classes");
        if (javac(cp + SEP + root.resolve("build/test-classes") + SEP + tcp,
                  junitClasses, sources(root.resolve("losim/test/junit"))) != 0) return 1;
        System.out.println("== a handler alone, in JUnit ==");
        Path console = one(root.resolve("vendor/test-jars"), "junit-platform-console-standalone-");
        if (console == null) {
            System.err.println("no junit console standalone jar in vendor/test-jars");
            return 1;
        }
        fail |= exec(List.of(java(), "-jar", console.toString(), "execute",
                "--class-path", lab + SEP + junitClasses,
                "--scan-class-path", junitClasses.toString(),
                "--details=tree", "--disable-ansi-colors", "--disable-banner")) != 0;
        return fail ? 1 : 0;
    }

    /**
     * The reference suite: gRPC systems a course could ship, run the way a
     * student runs one.
     *
     * <p>Different from {@link #test} on purpose. That one calls into losim's
     * classes; this one compiles against {@code build/losim.jar} and the vendored
     * gRPC alone, goes through the command line, and asserts against the trace on
     * disk — because the trace is the interchange format, and a build whose trace
     * was unreadable would pass every check in the other suite.
     */
    private int suite(List<String> named) throws Exception {
        Path out = root.resolve("build/tests");
        wipe(out);
        Files.createDirectories(out.resolve("gen"));
        Files.createDirectories(out.resolve("classes"));
        Files.createDirectories(out.resolve("traces"));

        if (protoc(root.resolve("tests/proto"), out.resolve("gen")) != 0) return 1;
        // Against the jar and the vendored gRPC, never against losim/src. That is
        // the rule a lab is under, and compiling the suite under it is how the
        // rule stays true.
        String cp = vendorJars() + jar();
        if (javac(cp, out.resolve("classes"), sources(out.resolve("gen"),
                  root.resolve("tests/systems"), root.resolve("tests/expect"))) != 0) return 1;

        Cases c = new Cases(cp + SEP + out.resolve("classes"), out);
        List<String> cases = named.isEmpty()
                ? List.of("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9",
                          "t10", "t11", "t12", "t13", "t14", "bill")
                : named;
        for (String name : cases) if (!c.one(name)) return 2;
        System.out.println();
        System.out.println(c.fail ? "reference suite: FAILED" : "reference suite: all cases passed");
        return c.fail ? 1 : 0;
    }

    /**
     * One case is one or more runs through the command line, and then an
     * assertion about what they wrote.
     *
     * <p>Two steps rather than one because several cases are about the
     * <i>difference</i> between two runs, and one of them is about a run that
     * must not start at all — so the run's own exit code is never the verdict.
     */
    private final class Cases {
        private final String lab;
        private final Path out;
        private boolean fail;

        Cases(String lab, Path out) { this.lab = lab; this.out = out; }

        /** @return false if there is no such case, which is the caller's error */
        boolean one(String name) throws Exception {
            switch (name) {
                case "t1" -> assertThat("T1");
                case "t2", "t3", "t4", "t5", "t6", "t9" -> {
                    run(name, name + ".yaml");
                    assertThat(name.toUpperCase(Locale.ROOT), trace(name), summary(name));
                }
                case "t7" -> {
                    run("t7", "t7.yaml");
                    run("t7-unsafe", "t7-unsafe.yaml");
                    assertThat("T7", trace("t7"), summary("t7-unsafe"));
                }
                case "t8" -> {
                    run("t8-tight", "t8-tight.yaml");
                    run("t8-roomy", "t8-roomy.yaml");
                    assertThat("T8", trace("t8-tight"), trace("t8-roomy"));
                }
                // The four engine cases. Each scaled run fits a plan from a grid
                // of about thirty small runs, which is where this suite's minutes
                // go — and it is the price of checking the one thing losim claims
                // that a smaller simulator does not.
                case "t10" -> {
                    t10();
                    assertThat("T10", trace("t10"), trace("t10-truth"));
                }
                // Over the traces t10 already wrote, so the one case that reads
                // nearly every channel costs no runs of its own.
                case "bill" -> {
                    if (!Files.exists(Path.of(trace("t10")))) t10();
                    assertThat("TBill", trace("t10"), trace("t10-truth"));
                }
                case "t11" -> {
                    List<String> cells = List.of("cluster2", "cluster4", "cluster8", "kill", "chaos");
                    for (String cell : cells) run("t11-" + cell, "t11-" + cell + ".yaml");
                    List<String> traces = new ArrayList<>();
                    for (String cell : cells) traces.add(trace("t11-" + cell));
                    assertThat("T11", traces.toArray(new String[0]));
                }
                case "t12" -> {
                    run("t12-spill", "t12-spill.yaml");
                    run("t12-overhead", "t12-overhead.yaml");
                    assertThat("T12", trace("t12-spill"), summary("t12-overhead"));
                }
                // One simulation, four times. The plan cache is keyed on the
                // telemetry level as well as on the code, or three of these would
                // silently reuse the first one's plan and the case would be
                // checking nothing at all.
                case "t13" -> {
                    run("t13-off", "t13.yaml", "--telemetry", "OFF");
                    run("t13-nopayload", "t13.yaml", "--telemetry", "NO_PAYLOAD");
                    run("t13-full", "t13.yaml", "--telemetry", "FULL");
                    run("t13-chatty", "t13-chatty.yaml", "--telemetry", "FULL");
                    assertThat("T13", trace("t13-off"), trace("t13-nopayload"),
                                      trace("t13-full"), trace("t13-chatty"));
                }
                // Both directions in one run: the same method, one deadline it can
                // meet and one it cannot. The summary is read as well as the trace,
                // because this exists to reach somebody who never opens a trace.
                case "t14" -> {
                    run("t14", "t14.yaml");
                    assertThat("T14", trace("t14"), summary("t14"));
                }
                default -> {
                    System.err.println("no such case: " + name);
                    return false;
                }
            }
            return true;
        }

        private void t10() throws Exception {
            run("t10", "t10.yaml");
            run("t10-truth", "t10-truth.yaml");
        }

        private String trace(String name)   { return out.resolve("traces/" + name + ".json").toString(); }
        private String summary(String name) { return out.resolve("traces/" + name + ".out").toString(); }

        /**
         * One run, exactly as a student would type it, with everything it said
         * kept beside the trace.
         *
         * <p>Its exit code is discarded on purpose: a run that fails is a result
         * here, and two cases assert on the summary of one that could not start.
         */
        private void run(String name, String simulation, String... extra) throws Exception {
            List<String> argv = new ArrayList<>(List.of(java(), "-Xmx3g", "-cp", lab,
                    "losim.cli.Main", "simulate", "--no-view",
                    root.resolve("tests/simulations/" + simulation).toString(),
                    "--cp", out.resolve("classes").toString(),
                    "--out", trace(name)));
            argv.addAll(List.of(extra));
            exec(argv, out.resolve("traces/" + name + ".out"));
        }

        private void assertThat(String klass, String... paths) throws Exception {
            List<String> argv = new ArrayList<>(List.of(java(), "-Xmx3g", "-cp", lab, klass));
            argv.addAll(List.of(paths));
            fail |= exec(argv) != 0;
        }
    }


    // ---------------------------------------------------------------------- the viewer

    /**
     * Everything about the viewer that is not the viewer: sweeping traces beside
     * it, building it, serving it, and the checks that read what it draws.
     *
     * <p>Two of these shell out to node and are meant to. {@code build} is
     * {@code next build} and nothing else, and the checks exist to prove the
     * TypeScript port agrees with the Python it was ported from — rewriting them
     * here would check the wrong thing.
     */
    private int viewer(String[] args) throws Exception {
        String what = args.length > 2 ? args[2] : "";
        List<String> rest = new ArrayList<>();
        for (int i = 3; i < args.length; i++) rest.add(args[i]);
        int port = Integer.parseInt(Main.option(args, "--port", "8000"));
        return switch (what) {
            case "traces" -> traces(rest);
            case "build"  -> npx(List.of("next", "build"));
            case "dev"    -> npx(List.of("next", "dev", "--port", String.valueOf(port)));
            case "check"  -> viewerCheck();
            case "serve"  -> {
                // The sweep is not allowed to stop the serve: a directory that
                // holds no runs yet is the ordinary state of a fresh clone, and
                // the picker says so better than an exit code does.
                traces(rest);
                Path site = root.resolve("viewer/out");
                if (!Files.isRegularFile(site.resolve("index.html"))) {
                    System.err.println("no export yet — bin/losim dev viewer build (needs npm, once)");
                    yield 1;
                }
                System.out.println();
                yield Serve.main(root.toString(), "viewer/out", "build/served",
                                 port, Main.host(), false, true);
            }
            default -> {
                System.err.println("losim dev viewer: traces, serve, build, dev or check");
                yield 2;
            }
        };
    }

    /**
     * Traces beside the exported app, with an index the picker can read and a
     * bill beside each one.
     *
     * <p><b>Only your own runs, unless asked.</b> The gallery is a hundred worked
     * examples written to develop and teach losim; a student who serves the
     * viewer should not receive thirty-six megabytes of somebody else's
     * afternoons. It is one flag away for whoever is working on the simulator.
     *
     * <p>Every run is tagged with where it came from, and the picker groups on
     * that with yours at the top — without it a first run appears as one line
     * among a hundred and five, alphabetically, between two of the gallery's.
     * Collisions go to whoever is more yours, and the shadowing is said out loud.
     */
    private int traces(List<String> args) throws Exception {
        Path into = root.resolve("build/served");
        Files.createDirectories(into);
        Files.createDirectories(root.resolve("build/traces"));

        // A bare path is yours — nobody names a directory that is not theirs —
        // and the two collections have to be asked for.
        List<String[]> sources = new ArrayList<>();
        boolean gallery = false, suite = false, named = false;
        for (String a : args) {
            switch (a) {
                case "--gallery" -> gallery = true;
                case "--suite"   -> suite = true;
                case "--all"     -> { gallery = true; suite = true; }
                default -> {
                    if (a.startsWith("--")) { System.err.println("unknown option: " + a); return 2; }
                    named = true;
                    sources.add(new String[]{"yours", a});
                }
            }
        }
        if (!named) sources.add(0, new String[]{"yours", "build/traces"});
        if (suite)   sources.add(new String[]{"suite", "build/tests/traces"});
        if (gallery) sources.add(new String[]{"gallery", "build/gallery/traces"});

        StringBuilder origins = new StringBuilder();
        List<String> seen = new ArrayList<>();
        int mine = 0;
        for (String[] source : sources) {
            String from = source[0];
            Path src = root.resolve(source[1]);
            if (!Files.exists(src)) continue;
            List<Path> found = Files.isDirectory(src) ? runsIn(src) : List.of(src);
            // A directory that exists and holds no traces is worth a word. This
            // once pointed a level too high and simply found nothing, so
            // twenty-two runs from the reference suite were quietly absent from
            // the picker for as long as nobody counted them.
            if (found.isEmpty()) {
                if (!from.equals("yours")) System.err.println("  " + source[1] + ": no traces here");
                continue;
            }
            for (Path f : found) {
                String name = f.getFileName().toString().replaceAll("\\.json$", "");
                // First writer wins, and the order is yours first. A run you made
                // under a name the gallery also uses is the one you meant.
                if (seen.contains(name)) {
                    System.err.println("  " + name + ": yours, so the " + from + " copy is not used");
                    continue;
                }
                seen.add(name);
                origins.append(name).append('\t').append(from).append('\n');
                if (from.equals("yours")) mine++;

                Path copy = into.resolve(f.getFileName());
                if (newer(f, copy)) Files.copy(f, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                // The bill, and only when it is missing or out of date — billing a
                // hundred traces takes long enough that doing it on every serve
                // would make this feel broken. Missing, the film still plays and
                // the money is simply absent, which is the right failure: a viewer
                // that invented its own prices would be a second accountant.
                Path billed = into.resolve(name + ".bill.json");
                if (newer(f, billed)) bill(f, billed);
            }
        }
        Files.writeString(into.resolve(".origins"), origins.toString());

        // A mirror of this sweep, not an accumulation of every sweep. Without
        // this, asking for the gallery once put a hundred runs in the picker
        // permanently: the index is built by listing the directory, so a
        // collection nobody asked for this time was still in it, tagged from an
        // .origins that no longer mentioned it and therefore reading as yours.
        for (Path p : children(into)) {
            String n = p.getFileName().toString();
            if (!n.endsWith(".json") || n.equals("index.json")) continue;
            String of = n.endsWith(".bill.json")
                    ? n.substring(0, n.length() - ".bill.json".length())
                    : n.substring(0, n.length() - ".json".length());
            if (!seen.contains(of)) Files.delete(p);
        }

        // The index the picker reads, from the same code that answers
        // `/traces/index.json` when losim is the server — so what a static server
        // shows and what losim shows cannot come apart.
        Files.write(into.resolve("index.json"), Serve.index(into, new java.util.HashMap<>()));
        // Counted per heading, because "25 traces" cannot say that the suite's
        // twenty-two arrived and yours did not.
        StringBuilder counts = new StringBuilder();
        for (String from : List.of("yours", "suite", "gallery")) {
            long n = origins.toString().lines().filter(l -> l.endsWith("\t" + from)).count();
            if (n == 0) continue;
            counts.append(counts.isEmpty() ? "" : ", ").append(n).append(' ').append(from);
        }
        System.out.println(seen.size() + " traces (" + counts + ") -> "
                + root.relativize(into.resolve("index.json")));

        if (mine == 0) {
            System.err.println("""

                  Nothing of yours in build/traces yet. To make one:
                    losim simulate yours.yaml --cp <your classes> --out build/traces/mine.json
                  Then `bin/losim dev viewer serve`, and it is the result the viewer opens.
                  (Working on losim itself? --gallery or --suite bring those in too.)""");
        }
        return 0;
    }

    /** Every trace in a directory: not the index, and not a bill. */
    private static List<Path> runsIn(Path dir) {
        List<Path> out = new ArrayList<>();
        for (Path p : children(dir)) {
            String n = p.getFileName().toString();
            if (n.endsWith(".json") && !n.endsWith(".bill.json") && !n.equals("index.json")) out.add(p);
        }
        return out;
    }

    private static boolean newer(Path source, Path than) throws IOException {
        if (!Files.exists(than) || Files.size(than) == 0) return true;
        return Files.getLastModifiedTime(source).compareTo(Files.getLastModifiedTime(than)) > 0;
    }

    /**
     * Priced in this JVM rather than in a hundred of them.
     *
     * <p>A trace that cannot be priced leaves no bill rather than an empty one:
     * the viewer treats a missing bill as money it does not know, and a zero-byte
     * file as a bill of nothing.
     */
    private void bill(Path trace, Path out) {
        try {
            Files.writeString(out, Bills.json(trace, root.resolve("prices/eu-central-1.yaml").toString()));
        } catch (Exception e) {
            System.err.println("  " + trace.getFileName() + ": no bill (" + e.getMessage() + ")");
            try { Files.deleteIfExists(out); } catch (IOException ignored) { /* nothing to remove */ }
        }
    }

    /**
     * The viewer's own checks, which are node and stay node.
     *
     * <p>Four of them are arithmetic; the rest are about what a browser does, and
     * a browser is where those are answered. They need a sweep to have happened,
     * because they check the port and the bill against runs that actually
     * occurred rather than against fixtures that agree with them by construction.
     */
    private int viewerCheck() throws Exception {
        Path index = root.resolve("build/served/index.json");
        String written = Files.isReadable(index) ? Files.readString(index) : "";
        // The index's contents, not the directory and not the file: a sweep writes
        // an empty index before it has anything to put in it, so both of those
        // prove nothing — and an empty run set is what two of these checks quietly
        // pass on and two others fail on for reasons that read like a regression.
        if (!written.replaceAll("\\s+", "").contains("\"runs\":[{")) {
            System.err.println("no traces yet — bin/losim dev viewer traces first");
            return 1;
        }
        boolean fail = false;
        for (String check : List.of("glyphs", "parity", "ledger", "cost",
                                    "console", "author", "stops")) {
            System.out.println();
            fail |= exec(List.of("node", "viewer/checks/" + check + ".ts")) != 0;
        }
        System.out.println();
        if (fail) System.err.println("viewer: something differs — see above");
        else System.out.println("viewer: all checks pass");
        return fail ? 1 : 0;
    }

    /** npm's runner, in viewer/, where the only node in this repository lives. */
    private int npx(List<String> args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("npx"));
        argv.addAll(args);
        return new ProcessBuilder(argv)
                .directory(root.resolve("viewer").toFile()).inheritIO().start().waitFor();
    }


    // ------------------------------------------------------------------------ the manual

    /**
     * The docs' own check: does the manual give an assignment away?
     *
     * <p>Two things in this course are the student's to write, so the manual must
     * not contain them: the batch-processing job, and logical time. A worked
     * example that slipped into a page would fail no build, would read like
     * helpful documentation, and would quietly hand in the answer.
     *
     * <p><b>The check is itself checked.</b> Every rule has a sample it must
     * catch and a sample it must not, and a rule that no sample proves works is a
     * failure rather than a pass — a regex with a typo catches nothing, passes
     * every scan, and looks exactly like a rule that is working.
     */
    private int docs(String[] args) throws Exception {
        String what = args.length > 2 ? args[2] : "";
        if (!what.equals("check")) {
            System.err.println("losim dev docs: check");
            return 2;
        }
        // Two sets, because two things are being checked. `everywhere` is what no
        // text may say at all; `docsOnly` is what the manual may not point a
        // student at. losim's own source refers to build/gallery/traces because
        // that is where it writes them, so scanning it with the path rules would
        // fail on the file running the check.
        List<Rule> everywhere = new ArrayList<>();
        everywhere.addAll(Rule.load(root.resolve("docs-check/leaks.txt"), false));
        everywhere.addAll(Rule.load(root.resolve("docs-check/shapes.txt"), true));
        List<Rule> rules = new ArrayList<>(everywhere);
        rules.addAll(Rule.load(root.resolve("docs-check/paths.txt"), false));
        if (Main.flag(args, "--rules")) {
            for (Rule r : rules) System.out.printf("  %-58s %s%n", r.source, r.why);
            return 0;
        }

        // ── the check's own test ──────────────────────────────────────────────
        System.out.println("== the check, checked ==");
        Path fixtures = root.resolve("docs-check/fixtures");
        List<Rule> proven = new ArrayList<>();
        boolean broken = false;
        List<Path> bad = named(fixtures, "bad-");
        if (bad.isEmpty()) {
            System.out.println("  no bad fixtures at all — the rules are unproven");
            return 2;
        }
        for (Path f : bad) {
            List<String> hits = scan(f, rules, proven);
            if (hits.isEmpty()) {
                System.out.println("  MISS  " + f + " should have been caught and was not");
                broken = true;
            } else {
                System.out.printf("  caught  %-42s %d rule(s)%n", f.getFileName(), hits.size());
            }
        }
        for (Path f : named(fixtures, "good-")) {
            List<String> hits = scan(f, rules, null);
            if (hits.isEmpty()) {
                System.out.println("  cleared " + f.getFileName());
            } else {
                System.out.println("  FALSE POSITIVE  " + f + " is innocent and was flagged:");
                hits.forEach(System.out::println);
                broken = true;
            }
        }
        for (Rule r : rules) {
            if (proven.contains(r)) continue;
            System.out.println("  UNPROVEN  no fixture is caught by /" + r.source
                    + "/ — write one, or the rule is decoration");
            broken = true;
        }
        if (broken) {
            System.out.println();
            System.out.println("the check itself is broken. Nothing was scanned.");
            return 2;
        }

        // ── the site's shape ──────────────────────────────────────────────────
        System.out.println();
        if (exec(List.of("node", "docs-check/structure.mjs", "docs")) != 0) return 1;

        // ── the scan ──────────────────────────────────────────────────────────
        System.out.println();
        System.out.println("== the site ==");
        // The simulator's own source is scanned too. Its javadoc is documentation
        // that happens to live in .java, and the repository is public because
        // release assets have to be downloadable without a token — so a worked
        // answer in a comment is as readable as one on a page. `|` and not `||`,
        // so both roots report rather than the first one hiding the second.
        List<Path> site = text(root.resolve("docs"));
        List<Path> src = text(root.resolve("losim/src"));
        boolean leaked = scanned(site, rules) | scanned(src, everywhere);
        int n = site.size() + src.size();
        System.out.println();
        if (leaked) {
            System.out.printf("%d files, and the manual gives an assignment away. "
                    + "Rewrite the page —%n", n);
            System.out.println("losim is teachable without either of them, which is the point"
                    + " of the rules.");
            return 1;
        }
        System.out.printf("%d files, nothing given away.%n", n);

        // ── and the copy that ships ───────────────────────────────────────────
        // The same rules against the jar's own resources, which is not the same
        // question: `processResources` copies a directory, and a directory that
        // grew a `fixtures/` would put every phrase these rules exist to catch
        // inside the artifact while `docs/` itself stayed clean.
        Path shipped = Bundled.dir("docs", "docs.json");
        if (shipped == null) {
            System.out.println("(this jar carries no manual, so there was nothing to check in it)");
            return 0;
        }
        List<Path> inside = text(shipped);
        if (scanned(inside, rules)) {
            System.out.println();
            System.out.println("the manual inside losim.jar carries what docs/ does not."
                    + " Look at what processResources copies.");
            return 1;
        }
        System.out.printf("%d files inside the jar, the same.%n", inside.size());
        return 0;
    }

    /** @return true if anything was given away, having printed every hit */
    private static boolean scanned(List<Path> files, List<Rule> rules) throws IOException {
        boolean leaked = false;
        for (Path f : files) {
            List<String> hits = scan(f, rules, null);
            hits.forEach(System.out::println);
            leaked |= !hits.isEmpty();
        }
        return leaked;
    }

    /**
     * One file against every rule.
     *
     * @param proven if given, every rule that fired is added to it — which is how
     *               a rule proves it works, rather than by being read
     */
    private static List<String> scan(Path f, List<Rule> rules, List<Rule> proven) throws IOException {
        List<String> out = new ArrayList<>();
        String whole;
        try {
            whole = Files.readString(f);
        } catch (Exception e) {
            return out;                       // not text; nothing here to give away
        }
        List<String> lines = whole.lines().toList();
        for (Rule r : rules) {
            boolean fired = false;
            if (r.wholePage) {
                boolean all = true;
                for (java.util.regex.Pattern term : r.terms) all &= term.matcher(whole).find();
                if (all) {
                    out.add("  " + f + "\n      the whole page: " + r.why
                            + "\n      all of: " + r.source);
                    fired = true;
                }
            } else {
                for (int i = 0; i < lines.size(); i++) {
                    if (!r.terms.get(0).matcher(lines.get(i)).find()) continue;
                    String line = lines.get(i);
                    out.add("  " + f + ":" + (i + 1) + "\n      "
                            + line.substring(0, Math.min(100, line.length()))
                            + "\n      matched /" + r.source + "/ — " + r.why);
                    fired = true;
                }
            }
            if (fired && proven != null && !proven.contains(r)) proven.add(r);
        }
        return out;
    }

    /**
     * A rule: what the manual may not contain, and what it would give away.
     *
     * <p>The patterns are written as extended regular expressions, because they
     * were written for {@code grep -E} and are still read by a person rather than
     * only by this. {@code [[:space:]]} is the one piece of that dialect Java does
     * not share — it would parse as a class of six ordinary characters and match
     * nearly everything — so it is translated here, and any other POSIX class is
     * refused rather than quietly reinterpreted. A rule that means something else
     * than it says is exactly what this whole check exists to prevent.
     */
    private record Rule(String source, String why, List<java.util.regex.Pattern> terms,
                        boolean wholePage) {

        static List<Rule> load(Path file, boolean wholePage) throws IOException {
            List<Rule> out = new ArrayList<>();
            if (!Files.isReadable(file)) return out;
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int at = trimmed.indexOf(":::");
                if (at < 0) throw new IllegalArgumentException(
                        file + ": a rule is <pattern>:::<what it gives away>, and this is not one:\n  " + trimmed);
                String source = trimmed.substring(0, at).strip();
                String why = trimmed.substring(at + 3).strip();
                List<java.util.regex.Pattern> terms = new ArrayList<>();
                for (String term : wholePage ? source.split("\\+") : new String[]{source}) {
                    terms.add(java.util.regex.Pattern.compile(java(term.strip()),
                            java.util.regex.Pattern.CASE_INSENSITIVE));
                }
                out.add(new Rule(source, why, terms, wholePage));
            }
            return out;
        }

        private static String java(String ere) {
            // The class, not the brackets around it: `[[:space:]_-]` is one
            // bracket expression holding a class and two more characters, and
            // `[\s_-]` is the same expression to Java.
            String out = ere.replace("[:space:]", "\\s").replace("[:alpha:]", "\\p{Alpha}")
                            .replace("[:digit:]", "\\d").replace("[:alnum:]", "\\p{Alnum}")
                            .replace("[:upper:]", "\\p{Upper}").replace("[:lower:]", "\\p{Lower}");
            if (out.contains("[:")) throw new IllegalArgumentException(
                    "no translation for the POSIX class in /" + ere + "/ — add one rather than"
                    + " letting Java read it as a handful of ordinary characters");
            return out;
        }

        // Identity, so that a coverage list is about which rule fired rather than
        // about two rules that happen to read the same.
        @Override public boolean equals(Object o) { return this == o; }
        @Override public int hashCode() { return System.identityHashCode(this); }
    }

    /** The fixtures with a given prefix, in name order. */
    private static List<Path> named(Path dir, String prefix) {
        List<Path> out = new ArrayList<>();
        for (Path p : children(dir)) {
            if (p.getFileName().toString().startsWith(prefix)) out.add(p);
        }
        return out;
    }

    /** Text under a directory: the site as it will be published. */
    private static final List<String> PUBLISHED = List.of(
            ".mdx", ".md", ".json", ".txt", ".yaml", ".yml", ".java", ".sh",
            ".proto", ".js", ".ts", ".tsx", ".css", ".svg");

    private static List<Path> text(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> PUBLISHED.stream().anyMatch(e -> p.getFileName().toString().endsWith(e)))
                    .sorted().toList();
        }
    }


    // ------------------------------------------------------------------------ the toolchain, vendored

    /**
     * losim's own schema, into {@code losim/src/losim/pb}.
     *
     * <p>Committed rather than built, for the same reason the vendored jars are:
     * protoc exists here for two platforms, so a build that ran it could not run
     * anywhere else. Generated code in {@code losim/src} is the price of losim
     * building on a machine nobody vendored a compiler for.
     *
     * <p>Reports whether anything changed, and that is the point of running it in
     * CI: the committed files and the {@code .proto} must be the same statement,
     * and a {@code .proto} edited without this verb is a schema the jar does not
     * actually ship.
     */
    private int proto() throws Exception {
        Path protos = root.resolve("losim/proto");
        Path into = root.resolve("losim/src/losim/pb");
        Path staged = Files.createTempDirectory("losim-proto");
        try {
            // The include root is losim/proto, so the descriptor records
            // "losim/job.proto" — the same string an assignment writes in its
            // import. Generated against a deeper root it would record "job.proto",
            // and the two would be different files to protobuf.
            if (protoc(protos, staged, List.of(protos.resolve("losim/job.proto"))) != 0) return 1;
            Path made = staged.resolve("losim/pb");
            var changed = new ArrayList<String>();
            for (Path fresh : children(made)) {
                String name = fresh.getFileName().toString();
                if (!name.endsWith(".java")) continue;
                Path old = into.resolve(name);
                if (!Files.exists(old) || !Files.readString(old).equals(Files.readString(fresh))) {
                    changed.add(name);
                }
                Files.createDirectories(into);
                Files.copy(fresh, old, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // package-info.java is written by hand and lives beside the generated
            // files; protoc does not produce it and must not be read as having
            // deleted it.
            for (Path had : children(into)) {
                String name = had.getFileName().toString();
                if (!name.endsWith(".java") || name.equals("package-info.java")) continue;
                if (!Files.exists(made.resolve(name))) {
                    Files.delete(had);
                    changed.add(name + " (gone)");
                }
            }
            if (changed.isEmpty()) {
                System.out.println("losim/src/losim/pb is what losim/proto says");
                return 0;
            }
            System.out.println("regenerated " + String.join(", ", changed));
            return 0;
        } finally {
            try (Stream<Path> walk = Files.walk(staged)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                    try { Files.deleteIfExists(f); } catch (IOException ignored) { }
                });
            }
        }
    }

    /**
     * The gRPC toolchain, into the repository.
     *
     * <p>losim must behave identically in the devcontainer, on a laptop and in a
     * Codespace (D10), which rules out resolving dependencies at build time. So
     * the jars live in git and the build stays javac and jar. This is how they
     * got here; nothing builds by it, and it needs a network.
     *
     * <p>Every version comes from {@code vendor/versions.properties}, which the
     * Gradle build reads too — a jar whose {@code vendor/jars} disagrees with the
     * POM it publishes does not get built.
     */
    private int vendor() throws Exception {
        Path dir = root.resolve("vendor");
        java.util.Properties pinned = new java.util.Properties();
        try (var in = Files.newInputStream(dir.resolve("versions.properties"))) {
            pinned.load(in);
        }
        for (String at : List.of("jars", "test-jars", "bin", "LICENSES")) {
            Files.createDirectories(dir.resolve(at));
        }

        System.out.println("jars:");
        String grpc = pin(pinned, "grpc");
        for (String a : List.of("grpc-api", "grpc-core", "grpc-stub", "grpc-protobuf",
                                "grpc-protobuf-lite", "grpc-inprocess", "grpc-context")) {
            get(dir.resolve("jars"), "io/grpc", a, grpc, null);
        }
        get(dir.resolve("jars"), "com/google/protobuf", "protobuf-java", pin(pinned, "protobuf"), null);
        get(dir.resolve("jars"), "com/google/api/grpc", "proto-google-common-protos",
                pin(pinned, "common-protos"), null);
        get(dir.resolve("jars"), "com/google/guava", "guava", pin(pinned, "guava"), null);
        get(dir.resolve("jars"), "com/google/guava", "failureaccess", pin(pinned, "failureaccess"), null);
        get(dir.resolve("jars"), "com/google/code/gson", "gson", pin(pinned, "gson"), null);
        get(dir.resolve("jars"), "com/google/android", "annotations",
                pin(pinned, "android-annotations"), null);
        get(dir.resolve("jars"), "org/codehaus/mojo", "animal-sniffer-annotations",
                pin(pinned, "animal-sniffer-annotations"), null);
        get(dir.resolve("jars"), "com/google/errorprone", "error_prone_annotations",
                pin(pinned, "error_prone_annotations"), null);
        get(dir.resolve("jars"), "io/perfmark", "perfmark-api", pin(pinned, "perfmark-api"), null);
        get(dir.resolve("jars"), "com/google/code/findbugs", "jsr305", pin(pinned, "jsr305"), null);

        // JUnit is kept apart from the runtime jars, because it belongs on the
        // classpath of a test and nowhere else. A lab's own code must not be able
        // to reach it — an import that compiles is a lesson that a handler is
        // something you assert about in isolation.
        System.out.println("test jars:");
        Path tests = dir.resolve("test-jars");
        get(tests, "org/junit/jupiter", "junit-jupiter-api", pin(pinned, "junit"), null);
        get(tests, "org/junit/platform", "junit-platform-console-standalone",
                pin(pinned, "junit-platform"), null);
        get(tests, "org/opentest4j", "opentest4j", pin(pinned, "opentest4j"), null);
        get(tests, "org/apiguardian", "apiguardian-api", pin(pinned, "apiguardian-api"), null);

        // protoc and the gRPC codegen plugin, for every platform a student may
        // open the repo on. Three, not two: a Codespace is linux-x86_64 and a
        // devcontainer on an Apple laptop is linux-aarch_64, and a student who
        // opened the container on the second would otherwise meet "cannot execute
        // binary file" as this course's first words.
        System.out.println("binaries:");
        for (String platform : List.of("osx-aarch_64", "linux-x86_64", "linux-aarch_64")) {
            get(dir.resolve("bin"), "com/google/protobuf", "protoc",
                    pin(pinned, "protobuf"), platform);
            get(dir.resolve("bin"), "io/grpc", "protoc-gen-grpc-java", grpc, platform);
        }

        System.out.println("licences:");
        Path licences = dir.resolve("LICENSES");
        download("https://www.apache.org/licenses/LICENSE-2.0.txt",
                 licences.resolve("Apache-2.0.txt"));
        download("https://raw.githubusercontent.com/protocolbuffers/protobuf/main/LICENSE",
                 licences.resolve("BSD-3-Clause-protobuf.txt"));
        download("https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.txt",
                 licences.resolve("EPL-2.0.txt"));
        Files.writeString(licences.resolve("README.md"), """
                Everything in `vendor/jars`, `vendor/test-jars` and `vendor/bin` is third-party
                and vendored unmodified from Maven Central.

                | project | licence |
                |---|---|
                | grpc-java (`io.grpc:*`) | Apache-2.0 |
                | protobuf-java, protoc (`com.google.protobuf:*`) | BSD-3-Clause |
                | proto-google-common-protos | Apache-2.0 |
                | Guava, failureaccess, gson, error_prone_annotations (`com.google.*`) | Apache-2.0 |
                | animal-sniffer-annotations (`org.codehaus.mojo`) | MIT |
                | perfmark-api (`io.perfmark`) | Apache-2.0 |
                | jsr305 (`com.google.code.findbugs`) | BSD-3-Clause |
                | JUnit 5 (`org.junit.*`), opentest4j, apiguardian-api | EPL-2.0 / Apache-2.0 |

                Apache-2.0 text: `Apache-2.0.txt`. Versions are pinned in `../versions.properties`.
                """);

        System.out.println();
        System.out.printf("vendored %d jars, %d test jars, %d binaries%n",
                children(dir.resolve("jars")).size(), children(tests).size(),
                children(dir.resolve("bin")).size());
        return 0;
    }

    private static String pin(java.util.Properties pinned, String name) {
        String v = pinned.getProperty(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("vendor/versions.properties pins no `" + name + "`");
        }
        return v.trim();
    }

    /**
     * One artifact from Maven Central, if it is not already here.
     *
     * <p>Never re-fetched: a vendored file is in git, so downloading it again
     * would either change nothing or replace a reviewed artifact with whatever is
     * published under that coordinate today. Delete the file to refetch it.
     */
    private static void get(Path into, String group, String artifact, String version,
                            String classifier) throws Exception {
        boolean exe = classifier != null;
        Path file = into.resolve(exe ? artifact + "-" + classifier : artifact + "-" + version + ".jar");
        String name = exe ? artifact + "-" + classifier : artifact + "-" + version;
        if (Files.exists(file)) { System.out.println("  have " + name); return; }
        System.out.println("  get  " + name);
        String url = "https://repo1.maven.org/maven2/" + group + "/" + artifact + "/" + version
                + "/" + artifact + "-" + version + (exe ? "-" + classifier + ".exe" : ".jar");
        download(url, file);
        if (exe) file.toFile().setExecutable(true);
    }

    private static void download(String url, Path to) throws Exception {
        try (var http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(20)).build()) {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(120)).build();
            var body = java.net.http.HttpResponse.BodyHandlers.ofFile(to);
            var response = http.send(request, body);
            if (response.statusCode() != 200) {
                Files.deleteIfExists(to);
                throw new IllegalArgumentException(url + " answered " + response.statusCode());
            }
        }
    }

    // ---------------------------------------------------------------- the toolchain

    private String jar() { return root.resolve("build/losim.jar").toString(); }

    private String vendorJars() {
        String jars = jars(root.resolve("vendor/jars"));
        return jars.isEmpty() ? "" : jars + SEP;
    }

    /** Every jar in a directory, in the order the filesystem lists them sorted. */
    private static String jars(Path dir) {
        StringBuilder sb = new StringBuilder();
        for (Path p : children(dir)) {
            if (p.getFileName().toString().endsWith(".jar")) {
                if (!sb.isEmpty()) sb.append(SEP);
                sb.append(p);
            }
        }
        return sb.toString();
    }

    private static Path one(Path dir, String prefix) {
        for (Path p : children(dir)) {
            if (p.getFileName().toString().startsWith(prefix)) return p;
        }
        return null;
    }

    private static List<Path> children(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Generate from every {@code .proto} in a directory.
     *
     * <p>The vendored compiler, not one on the path: {@code protoc} and the gRPC
     * plugin have to agree with the jars in {@code vendor/jars}, and a machine's
     * own protoc agrees with nothing in particular.
     */
    private int protoc(Path protos, Path gen, Path... alsoInclude) throws Exception {
        var files = new ArrayList<Path>();
        for (Path p : children(protos)) {
            if (p.getFileName().toString().endsWith(".proto")) files.add(p);
        }
        return protoc(protos, gen, files, alsoInclude);
    }

    /** The same, over an explicit list, when the files are not the include root's own children. */
    private int protoc(Path protos, Path gen, List<Path> files, Path... alsoInclude)
            throws Exception {
        Path bin = root.resolve("vendor/bin");
        Path compiler = bin.resolve("protoc-" + Lab.platform());
        Path plugin = bin.resolve("protoc-gen-grpc-java-" + Lab.platform());
        if (!Files.isExecutable(compiler) || !Files.isExecutable(plugin)) {
            System.err.println("no vendored protoc for " + Lab.platform());
            return 1;
        }
        Files.createDirectories(gen);
        List<String> argv = new ArrayList<>(List.of(compiler.toString(),
                "--plugin=protoc-gen-grpc-java=" + plugin,
                "--java_out=" + gen, "--grpc-java_out=" + gen,
                "-I", protos.toString()));
        // On the include path and never in the input list. A schema that is only
        // imported must not be generated again here: its classes are compiled
        // already, and a second copy of them is one nothing will ever load and
        // everything that counts schemas will miscount.
        for (Path also : alsoInclude) { argv.add("-I"); argv.add(also.toString()); }
        for (Path p : files) argv.add(p.toString());
        return exec(argv);
    }

    private int javac(String cp, Path classes, List<Path> sources) throws Exception {
        Files.createDirectories(classes);
        List<String> argv = new ArrayList<>(List.of(javac(), "-nowarn", "--release", "21",
                "-cp", cp, "-d", classes.toString()));
        for (Path p : sources) argv.add(p.toString());
        return exec(argv);
    }

    private static List<Path> sources(Path... dirs) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().forEach(out::add);
            }
        }
        return out;
    }

    /**
     * Run something with this terminal's streams, and hand back its exit code.
     *
     * <p>Inherited rather than piped: a JUnit tree drawn through a pipe loses the
     * order its two streams were written in, and the point of running these here
     * is that they read exactly as they did when a script ran them.
     */
    private int exec(List<String> argv) throws Exception {
        return new ProcessBuilder(argv).directory(root.toFile()).inheritIO().start().waitFor();
    }

    /** The same, with everything the process says kept in a file for a later assertion. */
    private int exec(List<String> argv, Path log) throws Exception {
        Files.createDirectories(log.getParent());
        return new ProcessBuilder(argv)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start().waitFor();
    }

    /** The JVM running this one, so a suite cannot end up on a different Java. */
    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String javac() {
        return Path.of(System.getProperty("java.home"), "bin", "javac").toString();
    }

    private static void wipe(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
