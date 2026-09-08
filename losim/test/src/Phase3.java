import java.nio.file.Path;
import java.util.*;
import losim.scale.*;
import losim.scenario.Loader;
import losim.scenario.Scenario;
import losim.scenario.Yaml;
import losim.trace.Telemetry;

/**
 * Phase 3: the scaler engine, which is what losim is for.
 *
 * <p>Everything before this could be described as a small simulator. This is the
 * part that claims something harder: that a run of eight thousand units can say
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

    static String cluster(String service, double scale) {
        return """
            mode: scaled
            seed: 9
            job: ScalableWordCount
            scale: %s
            nodes:
              master: { instance: m5.2xlarge, zone: z }
              workers:
                count: 4
                prefix: w
                instance: r5.large
                zone: z
                runs: [%s]
            input:
              lines:        %d
              wordsPerLine: 8
              vocabulary:   200000
            simulatedDuration:
              %s: { Map: { fixed: 2 refMs, perUnit: 20000 refNs }, Reduce: { fixed: 5 refMs } }
            """.formatted(trim(scale), service, Math.round(scale * Scenario.BASE), service);
    }

    /** A scale that reads as `6` rather than `6.0` in a file a person has to read. */
    static String trim(double scale) {
        return scale == Math.rint(scale) ? String.valueOf((long) scale) : String.valueOf(scale);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Phase 3 — projecting from what actually happened\n");
        declaredInput();
        groundTruth();
        refusal();
        transparency();
        planTravels();
        reconstruction();
        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------ where the workload size lives

    /**
     * The size of the input is in the file, and there is nowhere else to put it.
     *
     * <p>A job that hard-codes how much work it does cannot be swept, cannot be
     * overlaid, and — worse — does a different amount of work in a direct run from
     * the one the scale engine models. So the job declares what its input is made of
     * and the scenario says how much of each, and every way of getting that wrong is
     * refused with the line somebody wrote.
     *
     * <p>Each refusal is run backwards. One that has never fired has proven nothing.
     */
    static void declaredInput() throws Exception {
        System.out.println("=== the job declares the parts, the scenario declares the sizes ===");

        String cluster = """
            seed: 1
            job: Sizer
            nodes:
              master: { instance: m5.large, zone: z }
            """;
        var s = Loader.of(Yaml.parse("sized.yaml", cluster + """
            input:
              items:      240
              valueBytes: 65536
            """));
        var said = ran(s);
        check(said.equals("items=240 valueBytes=65536 units=240"),
              "a direct run does 240 items because the file says 240, not because the Java does");

        // The same job, told to do a fraction of it: what the scale engine does on
        // every rung of the ladder. Both parts are named in the same block and only
        // one of them moves.
        said = ran(Loader.of(Yaml.parse("sized.yaml", cluster + """
            scale: 6
            input:
              items:      240
              valueBytes: 65536
            """)).withUnits(1000));
        check(said.equals("items=5 valueBytes=65536 units=5"),
              "at a forty-eighth of full size it does five items — and still 65536 byte values, "
              + "because a constant is shape rather than size");

        check(refusedBy(Loader.of(Yaml.parse("sized.yaml", cluster)), ":2:")
                .contains("consumes 'items' and this scenario does not say how much"),
              "backwards: a job whose input is not sized is refused, at the job: line");

        check(refusedBy(Loader.of(Yaml.parse("sized.yaml", cluster + """
            input:
              items:      240
              valueBytes: 65536
              chunks:     4
            """)), ":8:").contains("consumes no part called 'chunks'"),
              "backwards: a part the job does not consume is refused, at its own line");

        check(refusedBy(Loader.of(Yaml.parse("sized.yaml", """
            seed: 1
            job: NoopJob
            scale: 6
            nodes:
              master: { instance: m5.large, zone: z }
            """)), ":2:").contains("has to implement losim.api.Scalable"),
              "backwards: a scenario that asks for a model of six times the run, and names a "
              + "job that cannot be asked for more, is refused at the job: line");

        check(refusedBy(Loader.of(Yaml.parse("sized.yaml", """
            seed: 1
            job: NoopJob
            nodes:
              master: { instance: m5.large, zone: z }
            input:
              items: 240
            """)), ":6:").contains("is a plain losim.api.Job, which is never given one"),
              "backwards: sizing an input for a job that takes none is refused");

        check(refusedBy(Loader.of(Yaml.parse("sized.yaml", cluster + """
            scale: 6
            input:
              items:      20
              valueBytes: 65536
            """)).withUnits(1000), ":2:").contains("shrinks to none at all"),
              "backwards: a count too small to survive the rung is refused rather than rounded "
              + "up, which would quietly change the design's ratios");

        try {
            Loader.of(Yaml.parse("sized.yaml", cluster + """
                input:
                  items: 0
                """));
            check(false, "backwards: a part sized zero is refused at load");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().contains("which is not a smaller run — it is no run"),
                  "backwards: a part sized zero is refused at load: " + e.getMessage());
        }
        System.out.println();
    }

    /** What the job logged, which is the only thing {@link Sizer} does. */
    static String ran(Scenario s) throws Exception {
        var result = losim.runtime.Run.of(s, loader());
        if (!result.completed()) throw new IllegalStateException("the run did not finish");
        return result.telemetry().events().stream()
                .filter(e -> e.kind().equals("log"))
                .map(e -> String.valueOf(e.detail().get("message")))
                .findFirst().orElse("nothing was logged");
    }

    /** The refusal a scenario earns, checked to carry the line it is about. */
    static String refusedBy(Scenario s, String line) throws Exception {
        try {
            losim.runtime.Run.of(s, loader());
        } catch (IllegalArgumentException e) {
            String said = e.getMessage();
            System.out.println("    " + said);
            return said.contains(line) ? said
                    : "the refusal does not name " + line + ": " + said;
        }
        return "it was not refused at all";
    }

    // ------------------------------------------------------- does it get it right

    /**
     * The only real test of the core contribution: project from a small probe to a
     * size the host <i>can</i> still hold, then actually run it and look.
     */
    static void groundTruth() throws Exception {
        System.out.println("=== against ground truth, and against the obvious alternative ===");
        // Four times the top of the engine's ladder: far enough that multiplying the
        // small run is visibly the wrong answer, near enough that the host can still
        // hold the run this is checked against.
        var s = Loader.of(Yaml.parse("truth.yaml", cluster("Accumulator", 4)));
        final long TRUTH = s.fullUnits();

        var grid = Grid.run(s, loader(), Telemetry.Level.FULL, Scaled.SEEDS);
        var rungs = grid.dataLadder().stream().map(Probe::medianOf).toList();
        long top = rungs.get(rungs.size() - 1).units();
        var laws = Laws.fit(grid, TRUTH / (double) top);
        System.out.print(laws.describe());

        // What actually happens at the size the engine was asked about.
        var actual = Probe.medianOf(List.of(
                Probe.run(s.withoutWeather().withUnits(TRUTH).withSeed(91), loader(), Telemetry.Level.FULL),
                Probe.run(s.withoutWeather().withUnits(TRUTH).withSeed(92), loader(), Telemetry.Level.FULL)));

        System.out.printf("%n  projecting %d -> %d units (x%.0f)%n", top, TRUTH, TRUTH / (double) top);
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
              "memory was attributed to distinct keys, not to units: peak reducer memory is "
              + "not a function of how many units there were, and fitting it against them "
              + "gives an exponent that will not survive a change of corpus");
        System.out.println();
    }

    // ----------------------------------------------------------- does it refuse

    static void refusal() throws Exception {
        System.out.println("=== a workload that changes its mind halfway up the ladder ===");
        Spiller.keepInMemory = 2200;
        var s = Loader.of(Yaml.parse("spill.yaml", cluster("Spiller", 500)));
        var grid = Grid.run(s, loader(), Telemetry.Level.FULL, 4);
        Spiller.keepInMemory = Integer.MAX_VALUE;

        var rungs = grid.dataLadder().stream().map(Probe::medianOf).toList();
        double[] units = rungs.stream().mapToDouble(Probe::units).toArray();
        double[] memory = rungs.stream()
                .mapToDouble(p -> p.resources().get(Probe.MEMORY)).toArray();
        System.out.printf("  memory across the ladder: %s%n",
                Arrays.stream(memory).mapToObj(m -> String.format("%.2f", m)).toList());

        double r2 = Fit.power(units, memory)[1];
        double diverge = Fit.halvesDiverge(units, memory);
        System.out.printf("  R2 over the whole ladder %.3f;  lower half beta %.2f, upper half %.2f"
                        + " (apart by %.2f)%n", r2,
                Fit.lowerBeta(units, memory), Fit.upperBeta(units, memory), diverge);

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
        var s = Loader.of(Yaml.parse("t.yaml", cluster("Accumulator", 500)));
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
     * The case a uniform factor gets wrong: a cluster with spare cores.
     *
     * <p>Four calls into eight cores take one wave. Thirty-two take four.
     * Multiplying the first run by eight says eight waves' worth of time for what
     * is four waves, and would tell a student their design is twice as slow as it
     * is.
     */
    static void reconstruction() throws Exception {
        System.out.println("=== the timeline is reconstructed, not multiplied ===");
        String yaml = """
            seed: 4
            job: BatchJob
            scale: %d
            nodes:
              master: { instance: m5.2xlarge, zone: z }
              workers:
                count: 4
                prefix: w
                instance: m5.large
                zone: z
                runs: [Slow]
            input:
              calls: %d
            simulatedDuration:
              Slow: { Hit: { fixed: 200 refMs }, Poll: { fixed: 200 refMs } }
            """;
        // Four 2-vCPU machines: eight cores. Observed under-saturated, projected
        // saturated. Four calls fit under eight cores; thirty-two are four waves of
        // them. The scale beside each is what the engine is asked to project across,
        // and the call count is now the scenario's to say rather than a division
        // buried in the job.
        var small = Loader.of(Yaml.parse("sched.yaml", yaml.formatted(2, 4)));
        var big = Loader.of(Yaml.parse("sched.yaml", yaml.formatted(16, 32)));

        var observed = losim.runtime.Run.of(small, loader(), Telemetry.Level.NO_PAYLOAD);
        var truth = losim.runtime.Run.of(big, loader(), Telemetry.Level.NO_PAYLOAD);

        // Every call costs the same 200 refMs whatever the run size, so the cost
        // site's law is flat and the whole question is the schedule.
        var projected = new HashMap<String, Double>();
        projected.put("losim.t.Volley.Poll", 200.0);

        var tasks = new ArrayList<Schedule.Task>();
        var perCall = Schedule.tasksOf(observed.telemetry(), projected);
        var vcpus = new HashMap<String, Integer>();
        for (var m : big.nodes()) vcpus.put(m.name(), 2);
        // Replay the observed graph at the size being asked about: the same shape,
        // eight times as many calls, dealt the same way round the same cluster.
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
        var s = Loader.of(Yaml.parse("plan.yaml", cluster("Accumulator", 500)));

        // This is the one test whose subject is the cache, so it is the one test that
        // cannot inherit an empty one from whoever ran it. `dev test` clears build/ on
        // the way in; running this class on its own (which is what you do while you
        // are working on it) does not, and then the *first* fit is a cache hit and
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
