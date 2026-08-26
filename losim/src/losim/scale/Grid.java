package losim.scale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import losim.scenario.Scenario;
import losim.trace.Telemetry;

/**
 * The probe grid: the same system, run small, several ways.
 *
 * <pre>
 *   data size      x  fleet size  x  fault schedule
 *   1k 2k 4k 8k    x  2, 4        x  none | the scenario's        x 3+ seeds, median taken
 * </pre>
 *
 * <p>Varying the axes <b>independently</b> is the whole reason this is a grid and
 * not a ladder. A resource that grows with the data and a resource that grows with
 * the fleet look identical if the two only ever move together, and an engine that
 * folds one into the other produces projections that are plausible and wrong — the
 * failure mode that is hardest to notice, because nothing looks broken.
 *
 * <p>The fault column is not optional either. A killed machine's work is redone
 * elsewhere, so a survivor absorbs its bucket: measured, that roughly doubles peak
 * reducer memory. A model fitted only on clean runs under-predicts by that much,
 * and it under-predicts <i>optimistically</i>.
 */
public record Grid(List<List<Probe>> dataLadder,
                   List<List<Probe>> fleetLadder,
                   List<Probe> clean,
                   List<Probe> weathered,
                   List<String> notes) {

    /** Seeds are drawn from the scenario's, so a plan is reproducible from the file. */
    public static long[] seedsFrom(long seed, int n) {
        var out = new long[n];
        for (int i = 0; i < n; i++) out[i] = seed * 1000L + i;
        return out;
    }

    public static Grid run(Scenario s, ClassLoader loader, Telemetry.Level level, int seedCount)
            throws Exception {
        var sizes = s.workload().probeSizes();
        var fleets = s.workload().fleetSizes();
        long[] seeds = seedsFrom(s.seed(), seedCount);
        var notes = new ArrayList<String>();

        int baseFleet = fleets.get(fleets.size() - 1);
        // The data ladder is climbed with the weather off, so what moves is the data.
        var bare = s.withoutWeather().withWorkers(baseFleet);

        var dataLadder = new ArrayList<List<Probe>>();
        for (int size : sizes) {
            var rung = new ArrayList<Probe>();
            for (long seed : seeds)
                rung.add(Probe.run(bare.withRecords(size).withSeed(seed), loader, level));
            dataLadder.add(rung);
        }

        // The fleet ladder holds the data still, so what moves is the fleet.
        int midSize = sizes.get(sizes.size() / 2);
        // The fleet ladder and the fault column are cross-checks rather than sources
        // of an error bar, so they are run at fewer seeds: only the data ladder's
        // exponent has to be shown to be reproducible.
        long[] few = Arrays.copyOf(seeds, Math.min(2, seeds.length));
        var fleetLadder = new ArrayList<List<Probe>>();
        for (int workers : fleets) {
            var rung = new ArrayList<Probe>();
            for (long seed : few)
                rung.add(Probe.run(s.withoutWeather().withWorkers(workers)
                        .withRecords(midSize).withSeed(seed), loader, level));
            fleetLadder.add(rung);
        }

        // And the fault column holds both still, so what moves is the weather.
        var clean = dataLadder.get(dataLadder.size() - 1);
        var weathered = new ArrayList<Probe>();
        if (s.faults().isEmpty() && s.chaos().isEmpty()) {
            notes.add("this scenario declares no faults, so the model carries no amplification "
                    + "term — a projection from it describes a fleet where nothing goes wrong");
        } else {
            int topSize = sizes.get(sizes.size() - 1);
            for (long seed : few)
                weathered.add(Probe.run(s.withWorkers(baseFleet).withRecords(topSize)
                        .withSeed(seed), loader, level));
        }
        return new Grid(dataLadder, fleetLadder, clean, weathered, notes);
    }

    /** How many runs this grid cost. Worth saying out loud, since the plan is cached on it. */
    public int runs() {
        int n = weathered.size();
        for (var rung : dataLadder) n += rung.size();
        for (var rung : fleetLadder) n += rung.size();
        return n;
    }

    /**
     * How much more a resource costs when things go wrong.
     *
     * <p>{@code demand = base(size, fleet) * amplification(faults)}. One is returned
     * when the scenario declares no weather, and that is recorded as a limit of the
     * model rather than as an absence of one.
     */
    public double amplification(String resource) {
        if (weathered.isEmpty()) return 1.0;
        double c = Probe.medianOf(clean).resources().getOrDefault(resource, 0.0);
        double w = Probe.medianOf(weathered).resources().getOrDefault(resource, 0.0);
        return c <= 0 ? 1.0 : Math.max(1.0, w / c);
    }
}
