package losim.time;

import java.util.concurrent.locks.LockSupport;

/**
 * The compressed clock, and the only thing in losim that sleeps.
 *
 * Two facts about the host decide how this is built.
 *
 * <p><b>parkNanos overshoots by a stable ratio, not a fixed offset</b> — about
 * 1.28 on a reference laptop, and the same ratio across three orders of
 * magnitude. That makes it a calibration problem rather than a resolution one:
 * measure the ratio at startup, per host, and divide every sleep by it.
 * Calibrated, mean error across the usable range falls from ~27% to ~3%.
 *
 * <p><b>Below about 0.05 ms nothing can be expressed at all</b>, because the
 * measurement's own overhead dominates the thing measured. A cost that fine is
 * therefore <i>owed</i> rather than slept, and settled later.
 *
 * <h2>Why the ledger is closed-loop</h2>
 * The debt is settled against what was <i>actually</i> slept, not against what
 * was asked for. Open-loop, the calibration's residual compounds across
 * thousands of tiny sleeps: 6.8% aggregate error typically and 16% at worst.
 * Crediting the overshoot back, so the next sleeps shorten to absorb it, brings
 * that to 0.3% and 2%. Costs fifty times below the floor still total correctly.
 *
 * <p>So {@code k_time} is <b>not</b> capped by timer resolution. What a sub-floor
 * cost loses is per-call observability, not aggregate time: ordering survives,
 * placement inside the debt window does not, and the trace has to say so.
 */
public final class Clock {

    /** Finer than this cannot be slept, only owed. */
    public static final double FLOOR_MS = 0.05;

    /** Calibration is fitted over the range it is applied to, starting here. */
    private static final double FIT_FROM_MS = FLOOR_MS;

    private final double kTime;
    private final double correction;
    private volatile long originNs = System.nanoTime();

    /** Owed nanoseconds, per thread: a debt is a property of whoever incurred it. */
    private final ThreadLocal<double[]> debt = ThreadLocal.withInitial(() -> new double[1]);

    public Clock(double kTime, double correction) {
        this.kTime = kTime;
        this.correction = correction;
    }

    /** A clock calibrated against this host, now. */
    public static Clock calibrated(double kTime) {
        return new Clock(kTime, measureCorrection());
    }

    public double kTime()      { return kTime; }
    public double correction() { return correction; }

    /**
     * Starts the clock now, discarding whatever setting up the fleet took.
     *
     * <p>Building six in-process servers costs real milliseconds, and at a k_time
     * of ten that is hundreds of reference milliseconds of a run that has not begun.
     * Left alone it puts every fault written for an early instant in the past, so
     * they all fire at once during setup — and it means an instant in the scenario
     * is not the same instant in the trace, which is the one correspondence a reader
     * needs.
     */
    public void restart() { originNs = System.nanoTime(); }

    /** Simulated milliseconds since this clock began — what the scenario is written in. */
    public double nowMs() { return (System.nanoTime() - originNs) / 1e6 * kTime; }

    /** Wall-clock nanoseconds since this clock began. */
    public long elapsedNs() { return System.nanoTime() - originNs; }

    /**
     * Sleeps a declared cost: reference-machine milliseconds, compressed by
     * {@code k_time}. Durations too fine to express are owed rather than lost.
     */
    public void spend(double refMs) {
        if (refMs <= 0) return;
        double[] owedNs = debt.get();
        owedNs[0] += refMs / kTime * 1e6;
        if (owedNs[0] < FLOOR_MS * 1e6) return;
        long before = System.nanoTime();
        LockSupport.parkNanos((long) (owedNs[0] / correction));
        owedNs[0] -= (System.nanoTime() - before);      // settle against reality, not intent
    }

    /** What this thread still owes, in reference milliseconds. */
    public double owedRefMs() { return debt.get()[0] / 1e6 * kTime; }

    /** A calibrated park of a real (uncompressed) duration. Used for waiting, not for costs. */
    public void parkRealNanos(long ns) {
        if (ns > 0) LockSupport.parkNanos((long) (ns / correction));
    }

    /**
     * Measures this host's park overshoot.
     *
     * <p>Fitted over the durations it will actually be applied to. Below the
     * floor the measurement's own overhead dominates, and including those points
     * would let the noisiest one set the calibration for every other.
     */
    public static double measureCorrection() {
        double[] targets = {0.05, 0.1, 0.25, 0.5, 1, 2};
        double sum = 0;
        int n = 0;
        for (double target : targets) {
            if (target < FIT_FROM_MS) continue;
            int reps = target <= 0.25 ? 200 : 60;
            long nanos = (long) (target * 1e6);
            long total = 0;
            for (int i = 0; i < reps; i++) {
                long t0 = System.nanoTime();
                LockSupport.parkNanos(nanos);
                total += System.nanoTime() - t0;
            }
            sum += (total / (double) reps) / (target * 1e6);
            n++;
        }
        double c = n == 0 ? 1.0 : sum / n;
        return c < 1.0 ? 1.0 : c;                        // a host that never overshoots needs none
    }
}
