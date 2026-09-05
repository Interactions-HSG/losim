package losim.scale;

import java.util.*;
import losim.runtime.Run;
import losim.scenario.Scenario;
import losim.trace.Telemetry;

/**
 * One run of a scenario, with every resource the engine needs measured rather
 * than declared.
 *
 * <h2>Sourcing the independent variables</h2>
 * The engine cannot fit a resource until it knows what that resource is a function
 * of, and getting that wrong is the failure mode that makes every projection
 * plausible and wrong. Peak reducer memory is not really a function of records at
 * all: it is a function of <i>distinct keys</i>, which is itself a sublinear
 * function of records. Fitted against keys it is near-perfect; fitted against
 * records it is a fragile exponent that will not survive a change of corpus.
 *
 * <p>So the candidates are collected rather than assumed. Records and the fleet
 * shape come from the scenario; calls and bytes are counted; and <b>every number
 * the program revealed is a candidate too</b>. That is the second thing
 * {@code Losim.current().reveal(...)} is for — a handler saying "this is the
 * quantity my cost depends on" in the one place that knows.
 */
public record Probe(
        long records, int workers, int faults, long seed,
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

    public static Probe run(Scenario s, ClassLoader loader, Telemetry.Level level) throws Exception {
        var result = Run.of(s, loader, level);
        return of(s, result);
    }

    public static Probe of(Scenario s, Run.Result result) {
        var tel = result.telemetry();
        var variables = new TreeMap<String, Double>();
        var resources = new TreeMap<String, Double>();

        variables.put("records", (double) s.records());
        variables.put("workers", (double) s.machines().stream()
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

        // Straight from the machines' own counters. A fleet does not run out of
        // memory on average, so memory and disk are the worst machine's peak; wire
        // and allocation are the fleet's total.
        resources.put(MEMORY, result.peakOf(t -> t.peakRetainedBytes() / 1048576.0));
        resources.put(DISK,   result.peakOf(t -> t.diskBytes() / 1048576.0));
        resources.put(ALLOC,  result.sumOf(t -> t.allocatedBytes() / 1048576.0));
        resources.put(WIRE,   result.sumOf(t -> t.bytesOut() / 1048576.0));
        resources.put(TIME,   Math.max(1e-6, result.durationRefMs()));

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

        int faults = s.faults().size() + s.chaos().size();
        return new Probe(s.records(), variables.get("workers").intValue(), faults, s.seed(),
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
        return new Probe(first.records(), first.workers(), first.faults(), first.seed(),
                variables, resources, costSites, all, failure);
    }

    private static Set<String> keysOf(List<Probe> runs,
                                      java.util.function.Function<Probe, Map<String, Double>> of) {
        var keys = new TreeSet<String>();
        for (Probe p : runs) keys.addAll(of.apply(p).keySet());
        return keys;
    }
}
