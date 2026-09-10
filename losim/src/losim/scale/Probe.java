package losim.scale;

import java.util.*;
import losim.runtime.Simulate;
import losim.sim.Simulation;
import losim.trace.Telemetry;

/**
 * One run of a simulation, with every resource the engine needs measured rather
 * than declared.
 *
 * <h2>Sourcing the independent variables</h2>
 * The engine cannot fit a resource until it knows what that resource is a function
 * of, and getting that wrong is the failure mode that makes every projection
 * plausible and wrong. Peak reducer memory is not really a function of units at
 * all: it is a function of <i>distinct keys</i>, which is itself a sublinear
 * function of units. Fitted against keys it is near-perfect; fitted against
 * units it is a fragile exponent that will not survive a change of corpus.
 *
 * <p>So the candidates are collected rather than assumed. Units and the cluster
 * shape come from the simulation; calls and bytes are counted; and <b>every number
 * the program revealed is a candidate too</b>. That is the second thing
 * {@code Losim.current().reveal(...)} is for — a handler saying "this is the
 * quantity my cost depends on" in the one place that knows.
 */
public record Probe(
        long units, int workers, int failures, long seed,
        Map<String, Double> variables,        // what a resource might be a function of
        Map<String, Double> resources,        // what it consumed
        Map<String, Double> costSites,        // per handler: what the program took, in refMs
        boolean completed, String failure) {

    /** Resource names the solver knows how to cap. */
    public static final String MEMORY = "memoryMb";
    public static final String DISK   = "diskMb";
    public static final String WIRE   = "wireMb";
    public static final String ALLOC  = "allocMb";
    public static final String TIME   = "makespanRefMs";

    /**
     * How a per-machine resource is named: {@code <machine>/<resource>}.
     *
     * <p>A separator no machine name can contain, so the two halves come apart
     * again without ambiguity. Machine names come from the {@code nodes:} block of
     * a simulation and are used as gRPC authorities, where a slash is not legal.
     */
    public static final char NODE_SEP = '/';

    public static String perNode(String machine, String resource) {
        return machine + NODE_SEP + resource;
    }

    /** Whether a resource key names one machine rather than the whole cluster. */
    public static boolean isPerNode(String key) {
        return key.indexOf(NODE_SEP) >= 0;
    }

    /** The machine half of a per-machine key, or null where the key is a cluster one. */
    public static String machineOf(String key) {
        int at = key.indexOf(NODE_SEP);
        return at < 0 ? null : key.substring(0, at);
    }

    /** The resource half of a per-machine key, or the key itself where it is a cluster one. */
    public static String resourceOf(String key) {
        int at = key.indexOf(NODE_SEP);
        return at < 0 ? key : key.substring(at + 1);
    }

    /**
     * Whether a cluster figure is the worst machine's, rather than everyone's added up.
     *
     * <p>A cluster does not run out of memory on average — one machine does — so
     * memory and disk are a peak, while wire and allocation are a total. Stated here
     * because the aggregation has to be known in two places: where the observed
     * figure is taken, and where the projected one is put back together.
     *
     * <p><b>And it must be put back together, not fitted.</b> {@code max} is not a
     * smooth function of anything. Over the ladder the peak is whichever machine
     * happens to be largest at probe size; the machine that overtakes it does so
     * outside the measured range, and a law fitted to the peak follows the wrong
     * series past the crossing. Measured here, that was a flat 11.44 MB where the
     * answer was 11.08 GB — understated by a factor of a thousand, at R² 1.0000 and
     * an error bar of one, because the fit is perfect. It is fitting the wrong thing
     * perfectly.
     */
    public static boolean isPeak(String resource) {
        return MEMORY.equals(resource) || DISK.equals(resource);
    }

    /** Whether a cluster figure is the sum of its machines'. The complement of {@link #isPeak}. */
    public static boolean isSum(String resource) {
        return ALLOC.equals(resource) || WIRE.equals(resource);
    }

    public static Probe run(Simulation s, ClassLoader loader, Telemetry.Level level) throws Exception {
        var result = Simulate.of(s, loader, level);
        return of(s, result);
    }

    public static Probe of(Simulation s, Simulate.Result result) {
        var tel = result.telemetry();
        var variables = new TreeMap<String, Double>();
        var resources = new TreeMap<String, Double>();

        variables.put("units", (double) s.units());
        variables.put("workers", (double) s.nodes().stream()
                .filter(m -> !m.runs().isEmpty()).count());

        long calls = tel.spans().stream().filter(sp -> sp.kind.equals("handler")).count();
        variables.put("calls", (double) calls);

        // Whatever the program revealed. The largest value each key reached is the
        // one that matters: a resource is sized by the peak it had to hold.
        var revealed = new TreeMap<String, Double>();
        for (var e : tel.events()) {
            if (!e.kind().equals("state")) continue;
            Object key = e.detail().get("key");
            Object value = e.detail().get("value");
            if (key == null || !(value instanceof Number n)) continue;
            revealed.merge(String.valueOf(key), n.doubleValue(), Math::max);
        }
        revealed.forEach((k, v) -> variables.put("revealed." + k, v));

        // Straight from the machines' own counters. A cluster does not run out of
        // memory on average, so memory and disk are the worst machine's peak; wire
        // and allocation are the cluster's total.
        resources.put(MEMORY, result.peakOf(t -> t.peakRetainedBytes() / 1048576.0));
        resources.put(DISK,   result.peakOf(t -> t.diskBytes() / 1048576.0));
        resources.put(ALLOC,  result.sumOf(t -> t.allocatedBytes() / 1048576.0));
        resources.put(WIRE,   result.sumOf(t -> t.bytesOut() / 1048576.0));
        resources.put(TIME,   Math.max(1e-6, result.durationRefMs()));

        // And the same four again, per machine, under a key that names the machine.
        //
        // A cluster total answers "what will this cost"; it cannot answer "which
        // node gives out first", and that is the question a reader of a scaled run
        // is usually asking. The peak and the sum both flatten exactly the thing
        // worth seeing: four renderers at ninety percent read the same on a total
        // as three at sixty and one at full.
        //
        // These are ordinary resource keys, so they are fitted by the same code as
        // the five above, against the same candidate variables, with the same bend
        // test and the same error bars — and refused one at a time. A node whose own
        // ladder bends is refused on its own, while the cluster it is part of keeps
        // whatever law it earned. Nothing here re-derives a node's share from a
        // total: a share is an assumption about a system growing evenly, and this is
        // a measurement of a system that may not.
        //
        // The data ladder is climbed on the cluster that will actually run, so these
        // names are the same at every rung. The cluster ladder varies the machine
        // count and therefore the names, but laws are fitted from the data ladder
        // alone — `Laws.fit` reads `grid.dataLadder()` — so nothing here is fitted
        // across two different shapes of system.
        for (Simulate.Result.Totals t : result.machines().values()) {
            resources.put(perNode(t.name(), MEMORY), t.peakRetainedBytes() / 1048576.0);
            resources.put(perNode(t.name(), DISK),   t.diskBytes() / 1048576.0);
            resources.put(perNode(t.name(), ALLOC),  t.allocatedBytes() / 1048576.0);
            resources.put(perNode(t.name(), WIRE),   t.bytesOut() / 1048576.0);
        }

        // Per cost site, the median of what the program took — median, not mean,
        // because one descheduled handler must not move a fitted exponent.
        var perSite = new TreeMap<String, List<Double>>();
        for (var span : tel.spans()) {
            if (!span.kind.equals("handler") || span.t1 < 0) continue;
            perSite.computeIfAbsent(span.label, k -> new ArrayList<>())
                   .add(span.programMs(tel.kTime()));
        }
        var costSites = new TreeMap<String, Double>();
        perSite.forEach((label, xs) -> costSites.put(label, median(xs)));

        return new Probe(s.units(), variables.get("workers").intValue(),
                s.failureCount(), s.seed(),
                variables, resources, costSites, result.completed(), result.failure());
    }

    static double median(List<Double> xs) {
        if (xs.isEmpty()) return 0;
        var sorted = new ArrayList<>(xs);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    /** The median of several probes, resource by resource — one slow run must not move a fit. */
    public static Probe medianOf(List<Probe> runs) {
        if (runs.isEmpty()) throw new IllegalArgumentException("no probes to take a median of");
        var first = runs.get(0);
        var variables = new TreeMap<String, Double>();
        var resources = new TreeMap<String, Double>();
        var costSites = new TreeMap<String, Double>();
        for (String k : keysOf(runs, Probe::variables))
            variables.put(k, median(runs.stream().map(r -> r.variables().get(k))
                    .filter(Objects::nonNull).toList()));
        for (String k : keysOf(runs, Probe::resources))
            resources.put(k, median(runs.stream().map(r -> r.resources().get(k))
                    .filter(Objects::nonNull).toList()));
        for (String k : keysOf(runs, Probe::costSites))
            costSites.put(k, median(runs.stream().map(r -> r.costSites().get(k))
                    .filter(Objects::nonNull).toList()));
        boolean all = runs.stream().allMatch(Probe::completed);
        String failure = runs.stream().map(Probe::failure).filter(Objects::nonNull)
                .findFirst().orElse(null);
        return new Probe(first.units(), first.workers(), first.failures(), first.seed(),
                variables, resources, costSites, all, failure);
    }

    private static Set<String> keysOf(List<Probe> runs,
                                      java.util.function.Function<Probe, Map<String, Double>> of) {
        var keys = new TreeSet<String>();
        for (Probe p : runs) keys.addAll(of.apply(p).keySet());
        return keys;
    }
}
