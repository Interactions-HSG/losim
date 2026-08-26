package losim.cli;

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
            System.err.println(e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(3);
        }
    }

    private static int run(String[] args) throws Exception {
        if (args.length == 0 || !args[0].equals("run")) {
            System.err.println("""
                usage: losim run <scenario.yaml> [options]

                  --cp <paths>       where the job and the services are compiled to
                  --out <file>       where to write the trace (default: build/<scenario>.json)
                  --seed <n>         override the scenario's seed, for a sweep
                  --telemetry <lvl>  FULL (default), NO_PAYLOAD or OFF""");
            return 2;
        }
        Path file = Path.of(need(args, 1, "which scenario?"));
        if (!Files.exists(file)) throw new IllegalArgumentException("no such scenario: " + file);

        String cp = option(args, "--cp", "");
        String out = option(args, "--out", null);
        String seed = option(args, "--seed", null);
        var level = Telemetry.Level.valueOf(option(args, "--telemetry", "FULL"));

        Scenario scenario = Loader.load(file);
        if (seed != null) scenario = withSeed(scenario, Long.parseLong(seed));

        var loader = classLoader(cp);
        Path target = Path.of(out != null ? out
                : "build/" + file.getFileName().toString().replaceAll("\\.ya?ml$", "") + ".json");

        if (scenario.mode() == Scenario.Mode.SCALED)
            return scaled(scenario, loader, level, cp, target);

        var result = Run.of(scenario, loader, level);
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

        // An invariant the scenario asserted and the run broke is a failure of the
        // run, not of losim — so it is worth an exit code a script can read.
        return result.completed() ? 0 : 1;
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
        var code = new ArrayList<Path>();
        for (String part : cp.split(java.io.File.pathSeparator))
            if (!part.isBlank()) code.add(Path.of(part));

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
        }
        for (String note : plan.notes()) System.out.println("  note: " + note);

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

    private static ClassLoader classLoader(String cp) throws Exception {
        if (cp.isEmpty()) return Main.class.getClassLoader();
        var urls = new ArrayList<URL>();
        for (String part : cp.split(java.io.File.pathSeparator))
            if (!part.isBlank()) urls.add(Path.of(part).toUri().toURL());
        return new URLClassLoader(urls.toArray(new URL[0]), Main.class.getClassLoader());
    }

    private static String need(String[] args, int i, String what) {
        if (i >= args.length) throw new IllegalArgumentException(what);
        return args[i];
    }

    private static String option(String[] args, String name, String fallback) {
        List<String> a = List.of(args);
        int i = a.indexOf(name);
        if (i < 0) return fallback;
        if (i + 1 >= a.size()) throw new IllegalArgumentException(name + " needs a value");
        return a.get(i + 1);
    }
}
