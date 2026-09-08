package losim.cli;

import java.io.Console;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import losim.scale.Scaled;
import losim.runtime.Simulate;
import losim.sim.Loader;
import losim.sim.Simulation;
import losim.trace.Telemetry;
import losim.verify.Trust;

/** Runs a simulation and writes the trace everything downstream reads. */
public final class Main {

    /**
     * Say what losim writes in, rather than in whatever the host guessed.
     *
     * <p>Every message here is UTF-8 in the jar — em-dashes, ellipses, the box
     * drawing in a plan — and a JVM picks its console encoding from the
     * environment. A machine with {@code LANG} unset, which is most CI runners and
     * a fair number of containers, gets ANSI_X3.4-1968, and every one of those
     * characters arrives as a question mark. A refusal that reads {@code its size
     * is a constant ? that is the whole problem} is a refusal somebody has to
     * decode before they can act on it.
     *
     * <p>Set before anything is printed, and only the streams — the JVM's own
     * {@code file.encoding} has been UTF-8 since 18 and is not the one at fault.
     */
    private static void speakUtf8() {
        System.setOut(utf8(java.io.FileDescriptor.out));
        System.setErr(utf8(java.io.FileDescriptor.err));
    }

    /**
     * Buffered, and flushed on every line. The stream it replaces is buffered too,
     * and a suite that prints ten thousand lines through an unbuffered one pays a
     * syscall for each of them.
     */
    private static java.io.PrintStream utf8(java.io.FileDescriptor fd) {
        return new java.io.PrintStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(fd), 8192),
                true, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void main(String[] args) {
        speakUtf8();
        try { System.exit(run(args)); }
        catch (IllegalArgumentException e) {
            // A simulation error is the user's, and should read like a compiler
            // error rather than like something went wrong inside losim.
            System.err.println(e.getMessage());
            System.exit(2);
        }
        catch (Exception e) {
            // Anything else is losim's, not the simulation's, and one line of it is
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
            // A container's attach hook cannot wait for a process whose whole
            // point is not to end, so it asks for the server to be left running
            // behind it. Handled here rather than inside Serve because it is
            // about how this command was invoked, not about what it serves.
            if (flag(args, "--detach")) return detach(args);
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
                return Manual.main(Manual.find(option(args, "--docs", "docs")),
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
        // prevent, so this alias must not fall through to the usage text below.
        if (args.length > 0 && args[0].equals("manual")) {
            return Manual.main(Manual.find(option(args, "--docs", "docs")),
                               Integer.parseInt(option(args, "--port", "3000")),
                               option(args, "--host", host()));
        }
        if (args.length > 0 && args[0].equals("version")) {
            return Released.main(args);
        }
        if (args.length > 0 && args[0].equals("adopt")) {
            return Adopt.main(args);
        }
        if (args.length > 0 && args[0].equals("check")) {
            // The same detector `adopt` printed from, so "what is left" is a
            // command rather than a memory of a terminal that has scrolled away.
            return Check.main(args);
        }
        if (args.length > 0 && args[0].equals("build")) {
            // Generate, compile, and say what is wrong — without running
            // anything. What the arrow in the lab does before it starts a run,
            // as a command, for whoever wants the compiler's answer and no more.
            Path base = Path.of(option(args, "--root", ".")).toAbsolutePath().normalize();
            Path classes = new Lab(base).compile(System.out::print);
            if (classes == null) return 1;
            System.out.println("compiled -> " + base.relativize(classes));
            return 0;
        }
        // Not in the usage text, and it refuses anywhere but here. See Dev.
        if (args.length > 0 && args[0].equals("dev")) {
            return Dev.main(args);
        }
        if (args.length > 0 && args[0].equals("compare")) {
            List<String> two = positionals(args);
            if (two.size() < 2) throw new IllegalArgumentException("compare needs two results");
            return Compare.of(Path.of(two.get(0)), Path.of(two.get(1)));
        }
        // Refused with the new name rather than aliased. An alias is a second
        // spelling that keeps working, and two spellings of one verb is how a
        // vocabulary comes apart again a year later.
        if (args.length > 0 && (args[0].equals("run") || args[0].equals("diff"))) {
            System.err.println(args[0].equals("run")
                    ? "losim run is losim simulate. A simulation is simulated and yields one"
                      + " result; there is no comparison inside one, which is what `run` used to"
                      + " leave open."
                    : "losim diff is losim compare. It takes two results, which is the only way"
                      + " two designs are ever compared.");
            return 2;
        }
        if (args.length == 0 || !args[0].equals("simulate")) {
            System.err.println("""
                usage: losim simulate <simulation.yaml> [options]

                  --cp <paths>       where the services are compiled to
                  --out <file>       where to write the trace (default: build/<name>.json)
                  --seed <n>         override the simulation's seed, for a sweep
                  --workers <n|+n>   resize every pool that has more than one node
                  --overlay <file>   lay a second file's weather over this one. Failures,
                                     retries, network and seed only — the system itself
                                     stays theirs
                  --telemetry <lvl>  FULL (default), NO_PAYLOAD or OFF
                  --no-view          write the trace and stop, without the viewer

                  At a terminal this opens the viewer on what it just simulated and
                  keeps it open. Simulate several files into one --out directory and
                  they are all in the picker, side by side.

                       losim serve [--port 8000] [--root .]

                  The lab, and it keeps running: the viewer, the results on disk, and
                  a way to build and simulate each system in the project. This is what a
                  devcontainer starts, so that nobody has to type any of the rest of
                  this.

                       losim serve docs [--port 3000] [--docs docs]

                  The manual, as a process of its own. Separate from the lab on
                  purpose: the manual is where you look when something will not
                  start, so it must not be served by the thing that will not start.

                       losim adopt [<dir>] [--force] [--dirty]

                  Put losim under a gRPC project that already works. Moves files,
                  writes a build, a launcher and a first simulation — and never
                  touches a .java, because guessing what a program means produces a
                  system its author did not write. It prints what is left, with a
                  line number for each, and writes the same list into AGENTS.md.

                       losim check [--root .]

                  Re-run those findings, without running the system.

                       losim build [--root .]

                  Generate from the schema and compile, and stop there. The arrow
                  in the lab does this before every simulation; this is the same
                  thing when what you want is the compiler's answer and no more.

                       losim compare <a.json> <b.json>

                  Whether two results came from the same simulator. Structure and
                  attribution have to agree; measurements are printed rather than
                  judged, because runs are not reproducible and hosts are not
                  identical. Two designs are two files, and this is how they meet.

                       losim version [--check]

                  Which losim this is. With --check, whether a newer one has been
                  released — the only command here that touches the network, and it
                  only does so when you type it. Updating is then one line in
                  build.gradle.kts, because that is where the version lives.

                       losim bill <trace.json> [--prices <file>] [--json]

                  What it cost, in four buckets. A scaled simulation is billed twice:
                  once for what happened, and once for the system it is a model of —
                  with the lines the engine could not project absent, and said so.""");
            return 2;
        }
        Path file = Path.of(positional(args, "which simulation?"));
        if (!Files.exists(file)) throw new IllegalArgumentException("no such simulation: " + file);

        // Not the JVM's own classpath, which is what the wrapper hands it and holds
        // no class of the lab's. See Lab.classesIfBuilt.
        String cp = option(args, "--cp",
                new Lab(Path.of(option(args, "--root", ".")).toAbsolutePath().normalize())
                        .classesIfBuilt());
        String out = option(args, "--out", null);
        String seed = option(args, "--seed", null);
        var level = Telemetry.Level.valueOf(option(args, "--telemetry", "FULL"));

        Simulation simulation = Loader.load(file);
        // A second file's weather over somebody else's cluster, for running their
        // design in a world they did not write. It may not touch the cluster.
        String over = option(args, "--overlay", null);
        if (over != null) simulation = Loader.overlay(simulation, Path.of(over));
        if (seed != null) simulation = withSeed(simulation, Long.parseLong(seed));

        // A wider or narrower cluster, without editing anybody's file. `+1` is
        // relative because the interesting question is almost never "run it on
        // four" — it is "run it on one more than it was written for", which is
        // where a routing scheme that counts machines comes apart.
        String workers = option(args, "--workers", null);
        if (workers != null) {
            int pool = biggestPool(simulation);
            simulation = simulation.withWorkers(workers.startsWith("+") || workers.startsWith("-")
                    ? Math.max(1, pool + Integer.parseInt(workers.substring(workers.charAt(0) == '+' ? 1 : 0)))
                    : Integer.parseInt(workers));
        }

        var loader = classLoader(cp);
        Path target = Path.of(out != null ? out
                : "build/" + file.getFileName().toString().replaceAll("\\.ya?ml$", "") + ".json");

        if (simulation.mode() == Simulation.Mode.SCALED) {
            int code = scaled(simulation, loader, level, cp, target);
            show(args, target);
            return code;
        }

        var result = Simulate.of(simulation, loader, level, Trust.of(simulation, paths(cp)));
        result.trace().writeTo(target);

        System.out.printf("%s  seed %d  %s in %.0f refMs%n", file.getFileName(), simulation.seed(),
                result.completed() ? "completed" : "did not complete", result.durationRefMs());
        if (result.failure() != null) System.out.println("  " + result.failure());
        System.out.print(wire(result));
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

        // An invariant the simulation asserted and the run broke is a failure of the
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
     * <p>Both modes come through here, so a scaled simulation and a direct one open
     * the viewer the same way: a student at a terminal gets one whichever kind of
     * run this was, and an explicit `--view` is always honoured.
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
    private static int scaled(Simulation s, ClassLoader loader, Telemetry.Level level,
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

        System.out.printf("%s  seed %d  scaled %,d -> %,d units (x%,.0f)%s%n",
                s.file(), s.seed(), plan.units(), plan.fullUnits(), plan.scaleFactor(),
                scaled.planWasCached() ? "  [plan cached]"
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

    private static Simulation withSeed(Simulation s, long seed) {
        return s.withSeed(seed);
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
    private static final List<String> BARE = List.of("--no-view", "--view", "--json",
            "--check", "--force", "--dirty", "--detach", "--no-open");

    /**
     * Every argument that is not a flag or a flag's value, in order.
     *
     * <p>Written as a scan rather than as `args[1]`, because `losim simulate --no-view
     * thing.yaml` must mean what it looks like it means: taking the second
     * argument on faith would read that as "no such simulation: --no-view", which
     * is the sort of message that sends somebody looking in the wrong place — and
     * `losim bill --prices expensive.yaml trace.json` would read the same way. So
     * every subcommand's arguments are found here, this way, rather than at a
     * fixed position.
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

    /** The first bare argument, or a fallback — for a verb whose subject is optional. */
    static String positionalOr(String[] args, String verb, String fallback) {
        List<String> found = positionals(args);
        return found.isEmpty() ? fallback : found.get(0);
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
     * in the picker: run three simulations into one directory and all three are
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
    private static int biggestPool(Simulation s) {
        var byPool = new java.util.LinkedHashMap<String, Integer>();
        for (var m : s.nodes()) byPool.merge(m.pool(), 1, Integer::sum);
        int most = 1;
        for (int n : byPool.values()) most = Math.max(most, n);
        return most;
    }

    /**
     * What happened on the wire, beside whether the job returned.
     *
     * <p>Those are two different facts and they looked like one. A cluster in which
     * 64 of 68 calls failed reported {@code completed in 5003 refMs} and nothing
     * else, because {@code run()} did return — the job's own counters said
     * {@code wrong=0}, which was true and meant nothing, since nothing it never
     * emitted can be wrong. Only a count of abandoned chunks said otherwise, and
     * that count existed because that job's author had thought to add one.
     *
     * <p>This needs no such foresight and knows nothing about what any job
     * computes. Calls, timeouts and errors are losim's own: the wire is its to
     * report, the way the money and the clock are. Where a job's correctness
     * begins is where this stops.
     */
    private static String wire(Simulate.Result result) {
        long calls = 0, timeouts = 0, errors = 0;
        for (var e : result.telemetry().events()) {
            switch (e.kind()) {
                case "rpc_call"    -> calls++;
                case "rpc_timeout" -> timeouts++;
                case "rpc_error"   -> errors++;
                default -> { }
            }
        }
        if (calls == 0) return "";
        long failed = timeouts + errors;
        if (failed == 0) return String.format("  %,d calls, none failed%n", calls);

        var sb = new StringBuilder(String.format("  %,d calls, %,d failed", calls, failed));
        if (timeouts > 0 && errors > 0) sb.append(String.format(" (%,d timed out, %,d errored)", timeouts, errors));
        else if (timeouts > 0)          sb.append(" (timed out)");
        else                            sb.append(" (errored)");
        sb.append(System.lineSeparator());

        // A deadline shorter than the callee's declared cost is a run that could
        // not have worked, and it is worth saying once rather than leaving in the
        // trace for whoever thinks to look. One line per method, not per call:
        // when this happens it happens to every call of that method.
        var unmeetable = new java.util.TreeMap<String, String>();
        for (var e : result.telemetry().events()) {
            if (!"rpc_timeout".equals(e.kind()) || e.detail().get("unmeetable") == null) continue;
            unmeetable.putIfAbsent(String.valueOf(e.detail().get("method")), String.format(
                    "    %s: the deadline was %s refMs and the handler declares at least %s",
                    e.detail().get("method"), refMs(e.detail().get("deadlineRefMs")),
                    refMs(e.detail().get("declaredRefMs"))));
        }
        // The subtler half, and the one people actually hit. The client can only
        // check a deadline against the fixed part of a cost, because the per-unit
        // part is not knowable until the handler declares its count. So a deadline
        // set above `refMs` and far below the real total times out with nothing said
        // above. The callee knows both by the time it answers, and says so on its
        // own span — reported here only where the client did not already, so one
        // mistake is not announced twice in two different phrasings.
        for (var s : result.telemetry().spans()) {
            if (s.detail.get("unmeetable") == null) continue;
            String method = s.label;
            if (unmeetable.containsKey(method)) continue;
            unmeetable.put(method, String.format(
                    "    %s: the deadline was %s refMs and the handler declares %s once "
                    + "its units are counted", method, refMs(s.detail.get("deadlineRefMs")),
                    refMs(s.detail.get("declaredRefMs"))));
        }

        for (String line : unmeetable.values()) sb.append(line).append(System.lineSeparator());
        if (!unmeetable.isEmpty())
            sb.append("    a deadline below the declared cost cannot be met on any host")
              .append(System.lineSeparator());
        return sb.toString();
    }

    /**
     * A declared duration, as a person wrote it.
     *
     * <p>The trace keeps the measured figure; a line meant to be read at a glance
     * does not need it. A deadline set at 200 refMs is read back as 199.972,
     * because a few microseconds pass between setting it and asking what is left,
     * and printing that invites somebody to wonder what happened to the 0.028.
     */
    private static String refMs(Object value) {
        if (!(value instanceof Number n)) return String.valueOf(value);
        double d = n.doubleValue();
        return Math.abs(d - Math.rint(d)) < 0.05 || d >= 10
                ? String.format("%.0f", d) : String.format("%.1f", d);
    }

    static boolean flag(String[] args, String name) {
        return List.of(args).contains(name);
    }

    /**
     * What a server here should bind to.
     *
     * <p>Inside a container the port is forwarded from outside, so a server on the
     * loopback interface has nothing listening on the one the browser reaches.
     * Every server this CLI starts asks this rather than writing an address down.
     */
    /**
     * The same command again, in a process this one does not wait for.
     *
     * <p>The port is probed first. Attaching to a container that is already up
     * must not start a second copy: the second would print "already running" and
     * then park, leaving a JVM behind for every attach, and after a morning of
     * reattaching that is a machine with no memory left and nothing to blame.
     *
     * <p>Everything it says goes to a file rather than nowhere, because the one
     * question worth asking about a detached process is why it is not there.
     */
    private static int detach(String[] args) throws Exception {
        int port = Integer.parseInt(option(args, "--port", "8000"));
        if (listening(port)) {
            System.out.printf("  :%d  already up%n", port);
            return 0;
        }
        boolean docs = args.length > 1 && (args[1].equals("docs") || args[1].equals("manual"));
        Path log = Path.of(option(args, "--root", ".")).resolve("build")
                .resolve((docs ? "manual" : "serve") + ".log");
        Files.createDirectories(log.getParent());

        List<String> argv = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "losim.cli.Main"));
        for (String a : args) if (!a.equals("--detach")) argv.add(a);
        // Nothing to open: there is no terminal waiting to be handed a browser,
        // and a detached process opening one is a window from nowhere.
        if (!docs && !flag(args, "--no-open")) argv.add("--no-open");
        new ProcessBuilder(argv)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
                .start();
        System.out.printf("  :%d  starting  (%s)%n", port, log);
        return 0;
    }

    /** Whether something already holds the port, asked the cheapest way there is. */
    private static boolean listening(int port) {
        try (var s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

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
     * <p>This decides whether `losim simulate` opens the viewer afterwards. A person at
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

    static String option(String[] args, String name, String fallback) {
        List<String> a = List.of(args);
        int i = a.indexOf(name);
        if (i < 0) return fallback;
        if (i + 1 >= a.size()) throw new IllegalArgumentException(name + " needs a value");
        return a.get(i + 1);
    }
}
