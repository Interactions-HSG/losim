package losim.runtime;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * The network between machines: how long a message takes, whether it arrives at
 * all, and who can currently reach whom.
 *
 * <p>Everything here is declared in <b>reference milliseconds</b> like every
 * other duration in losim (D3), so a scenario's latencies mean the same thing
 * whatever {@code k_time} the run ends up using.
 *
 * <p>Loss and a partition are the same event seen from the caller: the message
 * simply never arrives, and the caller waits out its own deadline. That is
 * deliberately not modelled as an immediate {@code UNAVAILABLE} — a socket that
 * refuses is a machine that answered, and the interesting failure is the one
 * where nothing answers at all.
 */
public final class Net {

    private volatile double sameZoneRefMs  = 0;
    private volatile double crossZoneRefMs = 0;
    private volatile double jitterRefMs    = 0;
    private volatile double loss           = 0;

    /** Ordered pairs that currently cannot reach each other, as "from|to". */
    private final Set<String> cut = ConcurrentHashMap.newKeySet();

    private final Random rng;

    public Net(long seed) { this.rng = new Random(seed); }

    public Net latency(double sameZone, double crossZone) {
        this.sameZoneRefMs = sameZone;
        this.crossZoneRefMs = crossZone;
        return this;
    }

    /** Uniform spread either side of the mean, in reference milliseconds. */
    public Net jitter(double refMs) { this.jitterRefMs = refMs; return this; }

    /** Probability that a message is simply never delivered. */
    public Net loss(double p) { this.loss = p; return this; }

    /** Cuts the link in both directions. */
    public Net partition(String a, String b) {
        cut.add(a + "|" + b);
        cut.add(b + "|" + a);
        return this;
    }

    public Net heal(String a, String b) {
        cut.remove(a + "|" + b);
        cut.remove(b + "|" + a);
        return this;
    }

    public boolean reaches(String from, String to) { return !cut.contains(from + "|" + to); }

    /** One round trip, in reference milliseconds. */
    double roundTripRefMs(String fromZone, String toZone) {
        double base = fromZone.equals(toZone) ? sameZoneRefMs : crossZoneRefMs;
        if (base <= 0 && jitterRefMs <= 0) return 0;
        double j = jitterRefMs <= 0 ? 0 : (nextDouble() * 2 - 1) * jitterRefMs;
        return Math.max(0, (base + j) * 2);          // out and back
    }

    boolean drops() { return loss > 0 && nextDouble() < loss; }

    private double nextDouble() {
        synchronized (rng) { return rng.nextDouble(); }
    }
}
