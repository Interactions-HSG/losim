package losim.cli;

import java.io.Console;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import losim.scale.Scaled;
import losim.runtime.Run;
import losim.scenario.Loader;
import losim.scenario.Scenario;
import losim.trace.Telemetry;
import losim.verify.Trust;

/** Runs a scenario and writes the trace everything downstream reads. */
public final class Main {

    public static void main(String[] args) {
        try { System.exit(run(args)); }
        catch (IllegalArgumentException e) {
            // A scenario error is the user's, and should read like a compiler
            // error rather than like something went wrong inside losim.
            System.err.println(e.getMessage());
            System.exit(2);
        }
        catch (Exception e) {
            // Anything else is losim's, not the scenario's, and one line of it is
            // not enough to find: the message names what went wrong and only the
            // stack names where. Kept behind a switch so an ordinary run still
            // reads like a tool rather than like a crash.
            System.err.println(e.getClass().getSimpleName() + ": " + e.getMessage());
            if (System.getenv("LOSIM_DEBUG") != null) e.printStackTrace();
            System.exit(3);
        }
    }

    private static int run(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("bill")) {
            return Bills.run(Path.of(positional(args, "bill needs a trace")),
                             option(args, "--prices", "prices/eu-central-1.yaml"),
                             flag(args, "--json"));
        }
        if (args.length > 0 && args[0].equals("serve")) {
            // Two things can be served and they are deliberately two processes.
            //
            //   losim serve        the lab: the viewer, your runs, and a button
            //                      beside every system in the project.
            //   losim serve docs   the manual, on its own port.
            //
            // Apart on purpose: the manual is what you read when something will
            // not start, so it must not be served by the thing that will not
            // start. A student whose lab is broken can still read how to fix it.
            boolean docs = args.length > 1 && (args[1].equals("docs") || args[1].equals("manual"));
            if (docs) {
                return Manual.main(Path.of(option(args, "--docs", "docs")),
                                   Integer.parseInt(option(args, "--port", "3000")),
                                   option(args, "--host", host()));
            }
            return Serve.main(option(args, "--root", "."),
                              option(args, "--site", null),
                              option(args, "--runs", null),
                              Integer.parseInt(option(args, "--port", "8000")),
                              option(args, "--host", host()),
                              !flag(args, "--no-open"), true);
        }
        // `losim manual` is the same thing as `losim serve docs`, kept because a
        // devcontainer somewhere is invoking it and a lab whose manual silently
        // did not start would fail in exactly the way the manual exists to
        // prevent. It fell through to the usage text once; it must not again.
        if (args.length > 0 && args[0].equals("manual")) {
            return Manual.main(Path.of(option(args, "--docs", "docs")),
                               Integer.parseInt(option(args, "--port", "3000")),
                               option(args, "--host", host()));
        }
        if (args.length > 0 && args[0].equals("diff")) {
            List<String> two = positionals(args);
            if (two.size() < 2) throw new IllegalArgumentException("diff needs two traces");
            return Diff.run(Path.of(two.get(0)), Path.of(two.get(1)));
        }
        if (args.length == 0 || !args[0].equals("run")) {
            System.err.println("""
                usage: losim run <scenario.yaml> [options]

                  --cp <paths>       where the job and the services are compiled to
                  --out <file>       where to write the trace (default: build/<scenario>.json)
                  --seed <n>         override the scenario's seed, for a sweep
                  --workers <n|+n>   resize every pool that has more than one machine
                  --overlay <file>   lay a second file's weather over this one. Faults,
                                     chaos, retries, network, seed and clock only — the
                                     fleet and the job stay theirs
                  --telemetry <lvl>  FULL (default), NO_PAYLOAD or OFF
                  --no-view          write the trace and stop, without the viewer

                  At a terminal this opens the viewer on what it just ran and keeps
                  it open. Run several scenarios into one --out directory and they
                  are all in the picker, side by side.

                       losim serve [--port 8000] [--root .]

                  The lab, and it keeps running: the viewer, the runs on disk, and a
                  way to build and run each system in the project. This is what a
                  devcontainer starts, so that nobody has to type any of the rest of
                  this.

                       losim serve docs [--port 3000] [--docs docs]

                  The manual, as a process of its own. Separate from the lab on
                  purpose: the manual is where you look when something will not
                  start, so it must not be served by the thing that will not start.

                       losim diff <a.json> <b.json>

                  Whether two traces are the same simulator. Structure and attribution
                  have to agree; measurements are printed rather than judged, because
                  runs are not reproducible and hosts are not identical.

                       losim bill <trace.json> [--prices <file>] [--json]

                  What the run cost, in four buckets. A scaled run is billed twice:
                  once for what happened, and once for the job it is a model of —
                  with the lines the engine could not project absent, and said so.""");
            return 2;
        }
        Path file = Path.of(positional(args, "which scenario?"));
        if (!Files.exists(file)) throw new IllegalArgumentException("no such scenario: " + file);

        String cp = option(args, "--cp", "");
        String out = option(args, "--out", null);
        String seed = option(args, "--seed", null);
        var level = Telemetry.Level.valueOf(option(args, "--telemetry", "FULL"));

        Scenario scenario = Loader.load(file);
        // A second file's weather over somebody else's fleet, for running their
        // design in a world they did not write. It may not touch the fleet.
        String over = option(args, "--overlay", null);
        if (over != null) scenario = Loader.overlay(scenario, Path.of(over));
        if (seed != null) scenario = withSeed(scenario, Long.parseLong(seed));

        // A wider or narrower fleet, without editing anybody's file. `+1` is
        // relative because the interesting question is almost never "run it on
        // four" — it is "run it on one more than it was written for", which is
        // where a routing scheme that counts machines comes apart.
        String workers = option(args, "--workers", null);
        if (workers != null) {
            int pool = biggestPool(scenario);
            scenario = scenario.withWorkers(workers.startsWith("+") || workers.startsWith("-")
                    ? Math.max(1, pool + Integer.parseInt(workers.substring(workers.charAt(0) == '+' ? 1 : 0)))
                    : Integer.parseInt(workers));
        }

        var loader = classLoader(cp);
        Path target = Path.of(out != null ? out
                : "build/" + file.getFileName().toString().replaceAll("\\.ya?ml$", "") + ".json");

        if (scenario.mode() == Scenario.Mode.SCALED) {
            int code = scaled(scenario, loader, level, cp, target);
            show(args, target);
            return code;
        }

        var result = Run.of(scenario, loader, level, Trust.of(scenario, paths(cp)));
        result.trace().writeTo(target);

        System.out.printf("%s  seed %d  %s in %.0f refMs%n", file.getFileName(), scenario.seed(),
                result.completed() ? "completed" : "did not complete", result.durationRefMs());
        if (result.failure() != null) System.out.println("  " + result.failure());
        System.out.printf("  %d events, %d spans, %d series -> %s%n",
                result.telemetry().events().size(), result.telemetry().spans().size(),
                result.telemetry().series().size(), target);
        for (var e : result.telemetry().events())
            if (e.kind().equals("oom") || e.kind().equals("disk_full"))
                System.out.printf("  %s ran out of %s: %s MB against a %s MB cap%n",
                        e.vm(), e.detail().get("resource"),
                        e.detail().get("demandMb"), e.detail().get("capMb"));
        System.out.print(result.trust().describe());

        show(args, target);

        // An invariant the scenario asserted and the run broke is a failure of the
        // run, not of losim — so it is worth an exit code a script can read.
        return result.completed() ? 0 : 1;
    }

    /**
     * Show what was just run, because a trace nobody looks at taught nobody
     * anything.
     *
     * <p>`--no-view` for a script that only wants the file; a script that forgot
     * to pass it is covered anyway, because a redirected stream is not a terminal
     * and this does not fire.
     *
     * <p>Both modes come through here. Written inline in the direct path, it was
     * unreachable for a scaled scenario — which returned before it — so a student
     * at a terminal got a viewer for one kind of run and silence for the other,
     * and an explicit `--view` was ignored.
     */
    private static void show(String[] args, Path target) {
        if (flag(args, "--no-view")) return;
        if (!flag(args, "--view") && !watched()) return;
        // Scaled mode that could not fit a size writes nothing, and a viewer
        // opened on a file that is not there says less than the reason it just
        // printed.
        if (!Files.exists(target)) return;
        view(args, target);
    }

    /**
     * Scaled mode: fit a plan (or find one cached), run what fits, print both scales.
     *
     * <p>A resource the engine refused to fit prints its reason instead of a number.
     * That is the whole discipline: a projection carries its confidence, or it is
     * absent — never filled in with something plausible.
     */
    private static int scaled(Scenario s, ClassLoader loader, Telemetry.Level level,
                              String cp, Path target) throws Exception {
        var code = paths(cp);

        long began = System.nanoTime();
        var scaled = Scaled.of(s, loader, level, code);
        var plan = scaled.plan();

        if (!plan.feasible()) {
            System.out.println(s.file() + "  scaled mode: no feasible size");
            System.out.println("  " + plan.infeasible());
            System.out.println("  Nothing was run and nothing is projected, which is the point:");
            System.out.println("  a projection that could not be made must be absent, not guessed.");
            return 1;
        }

        System.out.printf("%s  seed %d  scaled %,d -> %,d records (x%,.0f), k_time %.0f%s%n",
                s.file(), s.seed(), plan.records(), plan.fullRecords(), plan.scaleFactor(),
                plan.kTime(), scaled.planWasCached() ? "  [plan cached]"
                        : String.format("  [plan fitted from %d probe runs in %.1fs]",
                                plan.gridRuns(), (System.nanoTime() - began) / 1e9));
        System.out.println();
        System.out.print(plan.laws().describe());
        System.out.println();
        System.out.printf("  %-14s %16s   %20s%n", "", "observed", "projected");
        for (var p : scaled.projections()) {
            if (p.projected().isPresent())
                System.out.printf("  %-14s %16s   %20s  +-x%.2f%n", p.resource(),
                        human(p.observed()), human(p.projected().getAsDouble()), p.errorBar());
            else
                System.out.printf("  %-14s %16s   %20s%n      %s%n", p.resource(),
                        human(p.observed()), "- refused -", p.refusedBecause());

            // An error bar describes how well the law was fitted. It says nothing about
            // whether the measurement it was fitted to meant anything, and a machine
            // that stepped outside the simulated world is exactly that case — so the
            // caveat goes here, beside the number, not only in the block below.
            for (String caveat : scaled.trust().caveats(p.resource()))
                System.out.printf("      %s%n", caveat);
        }
        for (String note : plan.notes()) System.out.println("  note: " + note);
        System.out.println();
        System.out.print(scaled.trust().describe());

        scaled.run().trace().writeTo(target);
        System.out.printf("%n  %d events, %d spans -> %s%n",
                scaled.run().telemetry().events().size(),
                scaled.run().telemetry().spans().size(), target);
        return scaled.run().completed() ? 0 : 1;
    }

    private static String human(double v) {
        if (v >= 1e9) return String.format("%.2f G", v / 1e9);
        if (v >= 1e6) return String.format("%.2f M", v / 1e6);
        if (v >= 1e3) return String.format("%.2f k", v / 1e3);
        return String.format("%.3f", v);
    }

    private static Scenario withSeed(Scenario s, long seed) {
        return new Scenario(s.file(), seed, s.kTime(), s.job(), s.expectedRunRefMs(),
                s.machines(), s.net(), s.faults(), s.chaos(), s.retries(), s.tightMargin(),
                s.mode(), s.workload());
    }

    private static List<Path> paths(String cp) {
        var out = new ArrayList<Path>();
        for (String part : cp.split(java.io.File.pathSeparator))
            if (!part.isBlank()) out.add(Path.of(part));
        return out;
    }

    private static ClassLoader classLoader(String cp) throws Exception {
        if (cp.isEmpty()) return Main.class.getClassLoader();
        var urls = new ArrayList<URL>();
        for (String part : cp.split(java.io.File.pathSeparator))
            if (!part.isBlank()) urls.add(Path.of(part).toUri().toURL());
        return new URLClassLoader(urls.toArray(new URL[0]), Main.class.getClassLoader());
    }

    /** Flags that stand alone; everything else beginning with `--` takes a value. */
    private static final List<String> BARE = List.of("--no-view", "--view", "--json");

    /**
     * Every argument that is not a flag or a flag's value, in order.
     *
     * <p>Written as a scan rather than as `args[1]` because `losim run --no-view
     * thing.yaml` should mean what it looks like it means. Taking the second
     * argument on faith made that read as "no such scenario: --no-view", which is
     * the sort of message that sends somebody looking in the wrong place — and
     * `losim bill --prices expensive.yaml trace.json` was reading the same way,
     * so every subcommand's arguments are found here now rather than at two.
     */
    private static List<String> positionals(String[] args) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) { out.add(a); continue; }
            if (!BARE.contains(a)) i++;
        }
        return out;
    }

    private static String positional(String[] args, String what) {
        List<String> found = positionals(args);
        if (found.isEmpty()) throw new IllegalArgumentException(what);
        return found.get(0);
    }

    /**
     * Serve the viewer on the run that was just made, and keep serving it.
     *
     * <p>The trace's own directory is what is served, so `--out` decides what is
     * in the picker: run three scenarios into one directory and all three are
     * there to compare. It does not return — a viewer that closed itself the
     * moment it opened would be a screenshot.
     */
    private static void view(String[] args, Path trace) {
        int port = Integer.parseInt(option(args, "--port", "8000"));
        Path runs = trace.toAbsolutePath().getParent();
        System.out.println();
        try {
            Serve.main(".", null, runs.toString(), port, host(), true, false);
        } catch (Exception e) {
            System.out.println("the run is written; the viewer would not start (" + e.getMessage() + ")");
        }
    }

    /** How many machines the largest pool has, which is what `+1` is one more than. */
    private static int biggestPool(Scenario s) {
        var byPool = new java.util.LinkedHashMap<String, Integer>();
        for (var m : s.machines()) byPool.merge(m.pool(), 1, Integer::sum);
        int most = 1;
        for (int n : byPool.values()) most = Math.max(most, n);
        return most;
    }

    private static boolean flag(String[] args, String name) {
        return List.of(args).contains(name);
    }

    /**
     * What a server here should bind to.
     *
     * <p>Inside a container the port is forwarded from outside, so a server on the
     * loopback interface has nothing listening on the one the browser reaches.
     * Every server this CLI starts asks this rather than writing an address down.
     */
    static String host() { return contained() ? "0.0.0.0" : "127.0.0.1"; }

    /** Inside a container, where there is no browser and the port is forwarded out. */
    static boolean contained() {
        return System.getenv("CODESPACES") != null
                || System.getenv("REMOTE_CONTAINERS") != null
                || Files.exists(Path.of("/.dockerenv"));
    }

    /**
     * Whether a person is watching, as opposed to a script collecting a trace.
     *
     * <p>This decides whether `losim run` opens the viewer afterwards. A person at
     * a terminal wants to see what they just ran; the suite, the gallery and the
     * lab server all want the file and nothing else — and a server that started
     * itself in CI and never returned would hang the build. The distinction is
     * not a guess: a redirected stream is not a terminal.
     */
    private static boolean watched() {
        var console = System.console();
        if (console == null) return false;
        // `Console.isTerminal()` arrived in JDK 22 and this compiles to 21 (D10),
        // so it is asked for rather than called. It matters: from JDK 22 a
        // redirected stream still has a console, and without this every scripted
        // run would try to open a viewer nobody is looking at.
        try { return (Boolean) Console.class.getMethod("isTerminal").invoke(console); }
        catch (ReflectiveOperationException e) { return true; }
    }

    private static String option(String[] args, String name, String fallback) {
        List<String> a = List.of(args);
        int i = a.indexOf(name);
        if (i < 0) return fallback;
        if (i + 1 >= a.size()) throw new IllegalArgumentException(name + " needs a value");
        return a.get(i + 1);
    }
}
