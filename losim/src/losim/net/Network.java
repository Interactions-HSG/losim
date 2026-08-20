package losim.net;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Link latency, loss, partitions, and the three locality tiers. */
public final class Network {

    public double meanMs = 20;
    public double stddevMs = 5;
    public double loss = 0.0;
    /** Multiplier applied when the two VMs sit in different availability zones. */
    public double crossZoneFactor = 3.0;

    private final List<Set<String>> partitions = new ArrayList<>();

    /**
     * Extra delay on one direction of one link.
     *
     * Real networks are not uniform, and a message that arrives late while its
     * own reply arrives on time is how causal delivery stops being theoretical.
     */
    private final java.util.Map<String, Long> extraLatency = new java.util.LinkedHashMap<>();

    public void slowLink(String from, String to, long extraMs) {
        extraLatency.put(from + "->" + to, extraMs);
    }

    public long extraFor(String from, String to) {
        return extraLatency.getOrDefault(from + "->" + to, 0L);
    }

    public void partition(List<Set<String>> groups) {
        partitions.clear();
        for (Set<String> g : groups) partitions.add(new LinkedHashSet<>(g));
    }

    public void heal() { partitions.clear(); }

    public boolean blocked(String a, String b) {
        if (partitions.isEmpty() || a.equals(b)) return false;
        Integer ga = groupOf(a), gb = groupOf(b);
        if (ga == null || gb == null) return false;
        return !ga.equals(gb);
    }

    private Integer groupOf(String vm) {
        for (int i = 0; i < partitions.size(); i++) if (partitions.get(i).contains(vm)) return i;
        return null;
    }

    /** Locality tier, which drives both latency and whether egress is billed. */
    public enum Locality { LOOPBACK, SAME_ZONE, CROSS_ZONE }

    public Locality locality(String fromVm, String toVm, String fromZone, String toZone) {
        if (fromVm.equals(toVm)) return Locality.LOOPBACK;
        return fromZone.equals(toZone) ? Locality.SAME_ZONE : Locality.CROSS_ZONE;
    }

    /** Deterministic given the seeded rng. Bytes matter: bigger messages take longer. */
    public long latencyMs(Locality loc, long bytes, double netGbps, Random rng) {
        if (loc == Locality.LOOPBACK) return 0;
        double base = meanMs + rng.nextGaussian() * stddevMs;
        if (base < 1) base = 1;
        if (loc == Locality.CROSS_ZONE) base *= crossZoneFactor;
        double transferMs = (bytes * 8.0) / (netGbps * 1_000_000_000.0) * 1000.0;
        return Math.max(1, Math.round(base + transferMs));
    }

    public boolean dropped(Locality loc, Random rng) {
        return loc != Locality.LOOPBACK && loss > 0 && rng.nextDouble() < loss;
    }
}
