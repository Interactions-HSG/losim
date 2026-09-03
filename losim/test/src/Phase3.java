import java.nio.file.Path;
import java.util.*;
import losim.scale.*;
import losim.scenario.Loader;
import losim.scenario.Scenario;
import losim.scenario.Yaml;
import losim.trace.Telemetry;

/**
 * Phase 3 — the scaler engine, which is what losim is for.
 *
 * <p>Everything before this could be described as a small simulator. This is the
 * part that claims something harder: that a run of eight thousand records can say
 * what forty million would have done, and know when it cannot.
 *
 * <p>So the tests are about being <i>right</i> and about <i>refusing</i>, in that
 * order. A projection that is merely plausible is worse than no projection, because
 * it is indistinguishable from a good one until the cluster bill arrives.
 */
public class Phase3 {

    static int pass = 0, fail = 0;

    static void check(boolean ok, String what) {
        System.out.printf("  [%s] %s%n", ok ? "PASS" : "FAIL", what);
        if (ok) pass++; else fail++;
    }

    static ClassLoader loader() { return Phase3.class.getClassLoader(); }

    static String fleet(String service, long records, String probe) {
        return """
            mode: scaled
            seed: 9
            kTime: 40
            job: ScalableWordCount
            expectedRun: 20 refSeconds
            workload:
              records: %d
              probe: %s
              workers: [2, 4]
            machines:
              master: { instance: m5.2xlarge, zone: z }
              workers:
                count: 4
                prefix: w
                instance: r5.large
                zone: z
                runs: [%s]
            """.formatted(records, probe, service);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Phase 3 — projecting from what actually happened\n");
        groundTruth();
        refusal();
        transparency();
        planTravels();
        reconstruction();
        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------- does it get it right

    /**
     * The only real test of the core contribution: project from a small probe to a
     * size the host <i>can</i> still hold, then actually run it and look.
     */
    static void groundTruth() throws Exception {
        System.out.println("=== against ground truth, and against the obvious alternative ===");
        final int TRUTH = 16000;
        var s = Loader.of(Yaml.parse("truth.yaml", fleet("Accumulator", TRUTH, "[1000, 2000, 3000, 4000]")));

        var grid = Grid.run(s, loader(), Telemetry.Level.FULL, Scaled.SEEDS);
        var rungs = grid.dataLadder().stream().map(Probe::medianOf).toList();
        long top = rungs.get(rungs.size() - 1).records();
        var laws = Laws.fit(grid, TRUTH / (double) top);
        System.out.print(laws.describe());

        // What actually happens at the size the engine was asked about.
        var actual = Probe.medianOf(List.of(
                Probe.run(s.withoutWeather().withRecords(TRUTH).withSeed(91), loader(), Telemetry.Level.FULL),
                Probe.run(s.withoutWeather().withRecords(TRUTH).withSeed(92), loader(), Telemetry.Level.FULL)));

        System.out.printf("%n  projecting %d -> %d records (x%.0f)%n", top, TRUTH, TRUTH / (double) top);
        System.out.printf("  %-14s %10s %12s %10s   %12s %10s%n",
                "", "actual", "engine", "err", "uniform", "err");
        int enginesWon = 0, compared = 0;
        for (String resource : List.of(Probe.MEMORY, Probe.WIRE)) {
            if (!laws.has(resource)) {
                System.out.printf("  %-14s refused, so nothing to compare%n", resource);
                continue;
            }
            double truth = actual.resources().get(resource);
            double engine = laws.project(resource, TRUTH).orElseThrow();
            // The obvious alternative: multiply the top rung by the size ratio.
            double uniform = rungs.get(rungs.size() - 1).resources().get(resource)
                           * (TRUTH / (double) top);
            double eErr = Math.abs(engine - truth) / truth * 100;
            double uErr = Math.abs(uniform - truth) / truth * 100;
            System.out.printf("  %-14s %10.3f %12.3f %9.1f%%   %12.3f %9.1f%%%n",
                    resource, truth, engine, eErr, uniform, uErr);
            compared++;
            if (eErr < uErr) enginesWon++;
            check(eErr < 25, String.format(
                    "%s projects to within %.1f%% of what actually happened", resource, eErr));
        }
        check(compared > 0 && enginesWon == compared,
              "and on every resource it beats multiplying the small run by the size ratio — "
              + "which is the whole claim, since the uniform factor is what anyone would "
              + "reach for otherwise");

        var memory = laws.law(Probe.MEMORY);
        check(memory != null && memory.variable().equals("revealed.distinctKeys"),
              "memory was attributed to distinct keys, not to records: peak reducer memory is "
              + "not a function of how many records there were, and fitting it against them "
              + "gives an exponent that will not survive a change of corpus");
        System.out.println();
    }

    // ----------------------------------------------------------- does it refuse

    static void refusal() throws Exception {
        System.out.println("=== a workload that changes its mind halfway up the ladder ===");
        Spiller.keepInMemory = 2200;
        var s = Loader.of(Yaml.parse("spill.yaml", fleet("Spiller", 4000000, "[1000, 2000, 4000, 8000]")));
        var grid = Grid.run(s, loader(), Telemetry.Level.FULL, 4);
        Spiller.keepInMemory = Integer.MAX_VALUE;

        var rungs = grid.dataLadder().stream().map(Probe::medianOf).toList();
        double[] records = rungs.stream().mapToDouble(Probe::records).toArray();
        double[] memory = rungs.stream()
                .mapToDouble(p -> p.resources().get(Probe.MEMORY)).toArray();
        System.out.printf("  memory across the ladder: %s%n",
                Arrays.stream(memory).mapToObj(m -> String.format("%.2f", m)).toList());

        double r2 = Fit.power(records, memory)[1];
        double diverge = Fit.halvesDiverge(records, memory);
        System.out.printf("  R2 over the whole ladder %.3f;  lower half beta %.2f, upper half %.2f"
                        + " (apart by %.2f)%n", r2,
                Fit.lowerBeta(records, memory), Fit.upperBeta(records, memory), diverge);

        check(diverge > Fit.DISCONTINUITY,
              "splitting the ladder catches it: the halves disagree about the exponent");
        check(r2 > 0.85, String.format(
              "and R2 alone would NOT have caught it — it is still %.3f, a score a merely noisy "
              + "linear workload reaches just as easily, so no threshold on R2 separates bent "
              + "from noisy", r2));

        var laws = Laws.fit(grid, 500);
        String why = laws.refused().get(Probe.MEMORY);
        check(why != null && why.contains("bends"),
              "so the engine refuses the memory law rather than extrapolating across the spill");
        if (why != null) System.out.println("    " + why);

        var plan = Solve.of(s, grid, laws);
        boolean noProjection = !laws.has(Probe.MEMORY)
                && plan.projectionOf(Probe.MEMORY, 1).projected().isEmpty();
        check(noProjection,
              "and emits no projection at all for it — a field that is absent with a reason, "
              + "never one filled with a plausible number");
        System.out.println();
    }

    // ------------------------------------------------- does watching change it

    static void transparency() throws Exception {
        System.out.println("=== does how closely it is watched change what it projects? ===");
        var s = Loader.of(Yaml.parse("t.yaml", fleet("Accumulator", 4000000, "[1000, 2000, 4000, 8000]")));
        var exponents = new LinkedHashMap<Telemetry.Level, Double>();
        for (var level : List.of(Telemetry.Level.NO_PAYLOAD, Telemetry.Level.FULL)) {
            var grid = Grid.run(s, loader(), level, 2);
            var rungs = grid.dataLadder().stream().map(Probe::medianOf).toList();
            double[] keys = rungs.stream()
                    .mapToDouble(p -> p.variables().get("revealed.distinctKeys")).toArray();
            double[] memory = rungs.stream()
                    .mapToDouble(p -> p.resources().get(Probe.MEMORY)).toArray();
            exponents.put(level, Fit.power(keys, memory)[0]);
        }
        double bend = Math.abs(exponents.get(Telemetry.Level.FULL)
                             - exponents.get(Telemetry.Level.NO_PAYLOAD));
        System.out.printf("  memory exponent: %.4f without payloads, %.4f with them (bend %.4f)%n",
                exponents.get(Telemetry.Level.NO_PAYLOAD), exponents.get(Telemetry.Level.FULL), bend);
        check(bend < 0.02,
              "the law is the same whether or not every argument and result is being recorded — "
              + "rendering payloads is the most expensive thing losim does, and it is metered "
              + "and subtracted rather than hoped to be small");
        System.out.println();
    }

    // ------------------------------------------------------------ the timeline

    /**
     * The case a uniform factor gets wrong: a fleet with spare cores.
     *
     * <p>Four calls into eight cores take one wave. Sixteen take two. Multiplying
     * the first run by four says eight, and would tell a student their design is
     * four times slower than it is.
     */
    static void reconstruction() throws Exception {
        System.out.println("=== the timeline is reconstructed, not multiplied ===");
        String yaml = """
            seed: 4
            kTime: 20
            job: BatchJob
            expectedRun: 6 refSeconds
            workload: { records: %d, probe: [4, 8, 12, 16], workers: [4] }
            machines:
              master: { instance: m5.2xlarge, zone: z }
              workers:
                count: 4
                prefix: w
                instance: m5.large
                zone: z
                runs: [Slow]
            """;
        // Four 2-vCPU machines: eight cores. Observed under-saturated, projected saturated.
        var small = Loader.of(Yaml.parse("sched.yaml", yaml.formatted(4)));
        var big = Loader.of(Yaml.parse("sched.yaml", yaml.formatted(32)));

        var observed = losim.runtime.Run.of(small, loader(), Telemetry.Level.NO_PAYLOAD);
        var truth = losim.runtime.Run.of(big, loader(), Telemetry.Level.NO_PAYLOAD);

        // Every call costs the same 200 refMs whatever the run size, so the cost
        // site's law is flat and the whole question is the schedule.
        var projected = new HashMap<String, Double>();
        projected.put("losim.t.Volley.Poll", 200.0);

        var tasks = new ArrayList<Schedule.Task>();
        var perCall = Schedule.tasksOf(observed.telemetry(), projected);
        var vcpus = new HashMap<String, Integer>();
        for (var m : big.machines()) vcpus.put(m.name(), 2);
        // Replay the observed graph at the size being asked about: the same shape,
        // eight times as many calls, dealt the same way round the same fleet.
        for (int repeat = 0; repeat < 8; repeat++)
            for (var t : perCall)
                tasks.add(new Schedule.Task(t.id() + repeat * 10_000L, t.parent(), t.machine(),
                        t.projectedMs(), t.observedStart() + repeat * 1e-6, List.of()));

        var replay = Schedule.replay(tasks, vcpus);
        double multiplied = Schedule.multiplied(observed.durationRefMs(), 8);

        System.out.printf("  4 calls over 8 cores took %.0f refMs; 32 calls actually took %.0f%n",
                observed.durationRefMs(), truth.durationRefMs());
        System.out.printf("  reconstructed %.0f refMs (%s)%n", replay.makespanRefMs(), replay.note());
        System.out.printf("  multiplied    %.0f refMs%n", multiplied);

        double rErr = Math.abs(replay.makespanRefMs() - truth.durationRefMs())
                    / truth.durationRefMs() * 100;
        double mErr = Math.abs(multiplied - truth.durationRefMs()) / truth.durationRefMs() * 100;
        System.out.printf("  error: reconstruction %.0f%%, multiplication %.0f%%%n", rErr, mErr);
        check(rErr < mErr,
              String.format("replaying the call graph beats multiplying the makespan (%.0f%% "
                          + "against %.0f%%)", rErr, mErr));
        check(replay.tasks() == 32,
              "every call is a task in the replay, placed on the machine that would serve it");
        check(replay.makespanRefMs() > 700 && replay.makespanRefMs() < 1000,
              String.format("and it finds the four waves the cores actually impose (%.0f refMs "
                          + "against 4 x 200)", replay.makespanRefMs()));
        System.out.println();
    }

    // ------------------------------------------------------- does the plan travel

    /** Erase the fitted-plan cache, so that "was it cached?" has a knowable answer. */
    static void clearPlanCache() throws Exception {
        var dir = Path.of("build", ".losim-plans");
        if (!java.nio.file.Files.isDirectory(dir)) return;
        try (var walk = java.nio.file.Files.walk(dir)) {
            for (var f : walk.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(f);
        }
    }

    static void planTravels() throws Exception {
        System.out.println("=== the plan travels, and does not have to be paid for twice ===");
        var s = Loader.of(Yaml.parse("plan.yaml", fleet("Accumulator", 4000000, "[1000, 2000, 4000, 8000]")));

        // This is the one test whose subject is the cache, so it is the one test that
        // cannot inherit an empty one from whoever ran it. check.sh clears build/ on
        // the way in; running this class on its own — which is what you do while you
        // are working on it — does not, and then the *first* fit is a cache hit and
        // the assertion below reads as a broken simulator instead of a warm disk.
        clearPlanCache();

        long began = System.nanoTime();
        var first = Scaled.of(s, loader(), Telemetry.Level.FULL, List.of(Path.of("build/test-classes")));
        double fitted = (System.nanoTime() - began) / 1e9;

        began = System.nanoTime();
        var second = Scaled.of(s, loader(), Telemetry.Level.FULL, List.of(Path.of("build/test-classes")));
        double cached = (System.nanoTime() - began) / 1e9;

        System.out.printf("  %d probe runs the first time (%.1fs); the second run took %.1fs%n",
                first.plan().gridRuns(), fitted, cached);
        check(!first.planWasCached() && second.planWasCached(),
              "the plan is fitted once and cached against the scenario and the code it profiles");
        // Not `cached < fitted`: with both warm they are the same number and the
        // comparison is a coin toss. A fit is 28 probe runs against a lookup, which
        // is an order of magnitude, so ask for a margin that could not come up by
        // chance on a loaded machine.
        check(cached < fitted / 2,
              String.format("so a scaled run does not pay for the grid twice (%.1fs against %.1fs)",
                            cached, fitted));

        var json = second.run().trace().toJson();
        check(json.contains("\"scale\"") && json.contains("\"laws\"")
              && json.contains("\"projections\""),
              "and it travels in the trace, so projected = f(observed) is recomputable by "
              + "whoever reads it rather than something they have to take on trust");
        check(json.contains("\"refused\""),
              "including what the engine would not do, and why");

        var law = second.plan().laws().law(Probe.MEMORY);
        check(law != null && Math.abs(law.beta() - first.plan().laws().law(Probe.MEMORY).beta()) < 1e-9,
              "a plan read back out of the cache is the same plan, exponent for exponent");
        System.out.println();
    }
}
