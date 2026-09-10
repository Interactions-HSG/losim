package dissaly.scale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import dissaly.sim.Simulation;
import dissaly.trace.Telemetry;

/**
 * The probe grid: the same system, run small, several ways.
 *
 * <pre>
 *   data size      x  cluster size  x  fault schedule
 *   1k 2k 4k 8k    x  2, 4        x  none | the simulation's        x 3+ seeds, median taken
 * </pre>
 *
 * <p>Varying the axes <b>independently</b> is the whole reason this is a grid and
 * not a ladder. A resource that grows with the data and a resource that grows with
 * the cluster look identical if the two only ever move together, and an engine that
 * folds one into the other produces projections that are plausible and wrong — the
 * failure mode that is hardest to notice, because nothing looks broken.
 *
 * <p>The fault column is not optional either. A killed machine's work is redone
 * elsewhere, so a survivor absorbs its bucket: measured, that roughly doubles peak
 * reducer memory. A model fitted only on clean runs under-predicts by that much,
 * and it under-predicts <i>optimistically</i>.
 *
 * <p><b>The data ladder is warmed up and then climbed seed-major</b>, and both halves
 * of that are load-bearing. Every probe here runs in one JVM, so the order they are
 * run in decides which of them are executing interpreted code — and climbing
 * smallest-first put every cold run at the bottom rung. That is a systematic error on
 * the y axis correlated with the x axis, which no goodness-of-fit can see, because the
 * fit is computed from the same biased points. What it did was subtract from every
 * fitted exponent, which is how a design came to get cheaper the more of it there was.
 */
public record Grid(List<List<Probe>> dataLadder,
                   List<List<Probe>> clusterLadder,
                   List<Probe> clean,
                   List<Probe> weathered,
                   List<String> notes) {

    /** Seeds are drawn from the simulation's, so a plan is reproducible from the file. */
    public static long[] seedsFrom(long seed, int n) {
        var out = new long[n];
        for (int i = 0; i < n; i++) out[i] = seed * 1000L + i;
        return out;
    }

    public static Grid run(Simulation s, ClassLoader loader, Telemetry.Level level, int seedCount)
            throws Exception {
        var sizes = Simulation.LADDER;
        var counts = s.workerCounts();
        long[] seeds = seedsFrom(s.seed(), seedCount);
        var notes = new ArrayList<String>();

        // The data ladder is climbed on the cluster that will actually run — not on the
        // largest shape in the cluster ladder. A law fitted on eight workers and applied
        // to a run of two describes a differently-shaped system from the one being
        // measured, which is the same mistake as fitting on clean runs and predicting
        // a faulty one. The cluster ladder exists to cross-check that attribution, not
        // to decide what the data ladder is climbed on.
        int baseCluster = workersIn(s);
        // The data ladder is climbed with the weather off, so what moves is the data.
        var bare = s.withoutWeather().withWorkers(baseCluster);

        // Thrown away, and the ladder is not worth anything without it. Every probe in
        // this grid runs in one JVM, so the first few are executing interpreted code
        // against cold caches and the last few are not — and the ladder used to be
        // climbed smallest-first, which put every cold run at the bottom rung and every
        // warm one at the top. That is a bias on the y axis correlated with the x axis,
        // which is the one kind a fit cannot see: R², the residuals and the seed-set
        // wobble are all computed from the same biased points.
        //
        // It was not subtle once it was looked for. Reversing the ladder's order and
        // changing nothing else moved four consecutive fits of one unchanged file from
        // exponents of +0.02, -0.09, -0.03, +0.06 to +0.05, +0.44, +0.95, +0.06 — the
        // engine twice projecting that forty-eight thousand frames finish sooner than
        // eight thousand, because the small runs had been timed on a cold JVM.
        for (int size : sizes) Probe.run(bare.withUnits(size).withSeed(seeds[0]), loader, level);

        var dataLadder = new ArrayList<List<Probe>>();
        for (int i = 0; i < sizes.size(); i++) dataLadder.add(new ArrayList<>());
        // Seed-major, so each size is visited once early and once late. What warm-up
        // survives the pass above is then common to every rung instead of concentrated
        // in one of them, which lands it in the law's fixed term — where a constant
        // belongs — rather than in its exponent, where it was quietly deciding whether
        // the design got cheaper with scale.
        for (long seed : seeds)
            for (int i = 0; i < sizes.size(); i++)
                dataLadder.get(i).add(
                        Probe.run(bare.withUnits(sizes.get(i)).withSeed(seed), loader, level));

        // The cluster ladder holds the data still, so what moves is the cluster.
        int midSize = sizes.get(sizes.size() / 2);
        // The cluster ladder and the fault column are cross-checks rather than sources
        // of an error bar, so they are run at fewer seeds: only the data ladder's
        // exponent has to be shown to be reproducible.
        long[] few = Arrays.copyOf(seeds, Math.min(2, seeds.length));
        var clusterLadder = new ArrayList<List<Probe>>();
        for (int workers : counts) {
            var rung = new ArrayList<Probe>();
            for (long seed : few)
                rung.add(Probe.run(s.withoutWeather().withWorkers(workers)
                        .withUnits(midSize).withSeed(seed), loader, level));
            clusterLadder.add(rung);
        }

        // And the fault column holds both still, so what moves is the weather.
        var clean = dataLadder.get(dataLadder.size() - 1);
        var weathered = new ArrayList<Probe>();
        if (s.failureCount() == 0) {
            notes.add("this simulation declares no failures, so the model carries no "
                    + "amplification term — a projection from it describes a system where "
                    + "nothing goes wrong");
        } else {
            int topSize = sizes.get(sizes.size() - 1);
            for (long seed : few)
                weathered.add(Probe.run(s.withWorkers(baseCluster).withUnits(topSize)
                        .withSeed(seed), loader, level));
        }
        return new Grid(dataLadder, clusterLadder, clean, weathered, notes);
    }

    /** The cluster the simulation declares: every machine that serves something. */
    static int workersIn(Simulation s) { return s.workers(); }

    /** How many runs this grid cost. Worth saying out loud, since the plan is cached on it. */
    public int runs() {
        // Including the discarded warm-up pass, one per rung. It costs what a measured
        // run costs and reporting only the runs that were kept would understate what a
        // plan takes to fit by a seventh.
        int n = weathered.size() + dataLadder.size();
        for (var rung : dataLadder) n += rung.size();
        for (var rung : clusterLadder) n += rung.size();
        return n;
    }

    /**
     * How much more a resource costs when things go wrong.
     *
     * <p>{@code demand = base(size, cluster) * amplification(failures)}. One is returned
     * when the simulation declares no weather, and that is recorded as a limit of the
     * model rather than as an absence of one.
     */
    public double amplification(String resource) {
        if (weathered.isEmpty()) return 1.0;
        double c = Probe.medianOf(clean).resources().getOrDefault(resource, 0.0);
        double w = Probe.medianOf(weathered).resources().getOrDefault(resource, 0.0);
        return c <= 0 ? 1.0 : Math.max(1.0, w / c);
    }
}
