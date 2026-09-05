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
 * <h2>The ledger is closed-loop</h2>
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

    /**
     * Costs that ran out of parks with time still owed.
     *
     * <p>The one measure of "this host could not serve the time it was asked
     * for" that is about the time actually served, rather than about a property
     * of the host thought to predict it. A bound on how erratic the calibration
     * is, or on how large it comes out, refuses runs that the parking loop can
     * still serve correctly; this does not.
     *
     * <p>Zero on every host measured so far, busy and translated ones included.
     * Non-zero means a figure in this run is short by more than the floor, and
     * the trace says so rather than the terminal.
     */
    private final java.util.concurrent.atomic.AtomicLong unpaid =
            new java.util.concurrent.atomic.AtomicLong();

    /** How many declared costs could not be paid in full. Written into the trace. */
    public long unpaidCosts() { return unpaid.get(); }

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

        // Park until the debt is actually paid, rather than once and onwards.
        //
        // One park settles against reality, but a park lands short whenever the
        // correction is larger than this host's real overshoot *at this size*,
        // and it usually is. The overshoot is not one ratio: measured here it is
        // 1.33 at 0.05 ms and 1.02 at 200 ms, while the fit is taken over
        // 0.05-2 ms and applied to everything. So a 400 refMs cost was parked as
        // 400/1.28 and came back after 337, leaving 88 owed — and that residue
        // was paid inside the *next* handler's span, because the ledger is
        // per-thread and spans are not.
        //
        // A single park does not show up in the aggregate: ten such costs still
        // total 98% of ten, because the shortfall is a property of each call,
        // not of the sum. It shows up per call instead. `grossMs` closes at the
        // end of the span with the debt still outstanding, so a handler
        // declaring 400 would report 330, the first call of a run would report
        // worst, and a series of identical calls would read as a ramp climbing
        // towards its own declared value and never arriving — indistinguishable
        // from a bug in whatever the handler is doing.
        //
        // Looping fixes it without a better model of the host, which is the
        // point: each pass pays the fraction the correction happens to be right
        // about, and the remainder is re-parked. It converges for any correction
        // at or above 1, so a host whose ratio this cannot describe — a
        // translated one, a starved one — arrives at the same total by way of
        // more parks. Calibration becomes a question of how many parks, not of
        // whether the time is served.
        //
        // It ends at the floor, which is where the debt is meant to live: what
        // carries across calls is only what is too fine to express, never a
        // quarter of a declared cost.
        for (int pass = 0; pass < MAX_PARKS && owedNs[0] >= FLOOR_MS * 1e6; pass++) {
            long before = System.nanoTime();
            LockSupport.parkNanos((long) (owedNs[0] / correction));
            long slept = System.nanoTime() - before;
            owedNs[0] -= slept;                    // settle against reality, not intent
            // A park that returns at once returns at once every time: an
            // interrupt or a pending permit, neither of which more attempts
            // cure. Leaving the rest owed is right: it is the one thing this
            // ledger is built to carry.
            if (slept <= 0) break;
        }
        if (owedNs[0] >= FLOOR_MS * 1e6) unpaid.incrementAndGet();
    }

    /**
     * How many parks one cost may take before the rest is left owed.
     *
     * <p>A bound rather than a promise. With a correction this host can describe
     * it takes one pass or two; with one it cannot — a 6.6 measured under binary
     * translation against a real ratio near 1 — each pass pays about a seventh
     * and it takes on the order of fifty. Sixty-four leaves room for that and
     * still cannot spin: every pass either sleeps, and so reduces the debt, or
     * returns instantly and stops the loop.
     */
    private static final int MAX_PARKS = 64;

    /** What this thread still owes, in reference milliseconds. */
    public double owedRefMs() { return debt.get()[0] / 1e6 * kTime; }

    /** A calibrated park of a real (uncompressed) duration. Used for waiting, not for costs. */
    public void parkRealNanos(long ns) {
        if (ns > 0) LockSupport.parkNanos((long) (ns / correction));
    }

    /**
     * What the calibration found: the ratio to divide by, and how much the host
     * interfered while it was being measured.
     *
     * @param correction the park overshoot ratio, at least 1.0
     * @param noise      the share of parks that came back more than twice their
     *                   own median — 0 on a quiet host, and the thing that says
     *                   the correction beside it cannot be believed
     */
    public record Calibration(double correction, double noise) {

        /** Whether this host was still enough for the correction to mean anything. */
        public boolean quiet() { return noise <= NOISE_LIMIT; }

        /**
         * Whether the correction is one a real timer produces.
         *
         * <p>{@link #quiet()} measures *spread* and this measures *level*, and
         * they fail on different machines. A host under load produces erratic
         * parks: the spread gives it away and the median survives it. A host
         * running translated x86 under Rosetta produces parks that are slow and
         * perfectly consistent — noise 0.001, cleaner than some idle native
         * runs — with a correction of 6.58 against a native 1.28. Nothing about
         * the spread is wrong there, so nothing about the spread can catch it,
         * and the run served 53% of its declared work with a trace that said
         * trusted: true.
         */
        public boolean plausible() { return correction <= CORRECTION_LIMIT; }

        /** Both, which is what a run needs before its durations mean anything. */
        public boolean usable() { return quiet() && plausible(); }
    }

    /**
     * The largest park overshoot a host can report and still be believed.
     *
     * <p>Measured, like {@link #NOISE_LIMIT}, rather than chosen. Every native
     * host seen so far fits in a very narrow band, and the one translated host
     * seen is five times outside it:
     *
     * <pre>
     *   host                              correction   noise
     *   arm64 macOS, idle                  1.279-1.281   0.000
     *   arm64 macOS, second machine              1.295   0.000
     *   x86_64 under Rosetta, quiet              6.579   0.001
     * </pre>
     *
     * <p>2.0 sits with 54% of headroom above the highest native figure and a
     * factor of 3.3 below the translated one. It is deliberately generous: this
     * is meant to catch a host whose timer is a different kind of thing, not to
     * adjudicate between two ordinary laptops.
     *
     * <p>Why a bound and not a better model: under translation the overshoot
     * stops being a ratio at all. Even natively the ratio decays with magnitude
     * — 1.333 at 0.05 ms, 1.26 at 2 ms, 1.02 at 200 ms — and the closed-loop
     * ledger is what absorbs that. Translation stretches the small end far
     * enough that a fit over 0.05-2 ms says nothing about a 200 ms cost, and no
     * summary statistic over that range can rescue it. The honest answer is to
     * say the host cannot be calibrated.
     */
    public static final double CORRECTION_LIMIT = 2.0;

    /**
     * How wild a park has to be to count as interference: twice its own median.
     *
     * <p>Not a percentile of a distribution nobody has, and not a fixed number of
     * nanoseconds — the floor and the ceiling of this fit are forty times apart,
     * so the only scale a threshold can be in is the target's own.
     */
    private static final double WILD = 2.0;

    /**
     * How many parks may be wild before the calibration is refused.
     *
     * <p>Measured rather than chosen. On this fit, with busy loops pinned to a
     * varying number of a twelve-core host's cores:
     *
     * <pre>
     *   load          correction found   parks over 2x median
     *   idle                     1.279                   0.0%
     *   3 of 12 cores            1.278                   0.0%
     *   6 of 12 cores            1.281                   0.1-0.4%
     *   11 of 12 cores      1.74-2.19                 14.7-19.4%
     *   oversubscribed      1.99-2.36                 20.4-21.8%
     * </pre>
     *
     * <p>Two facts fall out of that table. The first is that the correction is
     * right — 1.28, the documented figure — until the host runs out of cores
     * entirely, and then it is wrong by nearly a factor of two. The second is
     * that the gap between those two states is empty: nothing observed sits
     * between 0.4% and 14.7%. So the limit goes in the middle of the gap, with an
     * order of magnitude of margin on each side, and a threshold picked this way
     * refuses runs that would have been wrong without refusing any that would
     * have been right.
     */
    public static final double NOISE_LIMIT = 0.05;

    /**
     * Measures this host's park overshoot, and whether it could be measured.
     *
     * <p>Fitted over the durations it will actually be applied to. Below the
     * floor the measurement's own overhead dominates, and including those points
     * would let the noisiest one set the calibration for every other.
     *
     * <p><b>Summarised by the median, not the mean.</b> A mean over parks is the
     * one statistic a busy host destroys: a single descheduled sample of 50 ms
     * among two hundred parks of 0.05 ms adds 0.25 ms to their average — five
     * times the thing being measured — so the correction comes back at 2.4
     * instead of 1.28 and every declared duration sleeps at half its length. The
     * same jar, run twenty minutes apart, can bill a handler 200 refMs and then
     * 100, with nothing to say why: the trace's {@code trusted} flag covers what
     * the code reads, not what the clock could serve. {@link Calibration#noise()}
     * covers the clock, and is written into every trace.
     */
    public static Calibration calibrate() {
        // Up to three attempts, stopping at the first quiet one.
        //
        // A host is busy for reasons that pass: a build finishing, an indexer, a
        // container's neighbour. Refusing on the first noisy half-second would
        // turn "something else was compiling" into a run that would not start,
        // and the fix — measure again in a moment — is one this can do itself.
        // What it must not do is retry until it gets the answer it likes, so a
        // host that is *persistently* starved still hands back its quietest
        // attempt and is still refused on it. Meter.calibrate() takes the best
        // of three rounds for the same reason, and resists the kind of
        // transient noise a single mean-based pass does not.
        var rounds = new java.util.ArrayList<Calibration>();
        for (int round = 0; round < ROUNDS; round++) {
            Calibration c = measure();
            if (c.quiet()) return c;
            // Retrying answers a host that was momentarily busy. It cannot answer
            // a host whose timer is simply slow — that figure is stable, and
            // measuring it twice more only takes longer to say the same thing.
            if (!c.plausible()) return c;
            rounds.add(c);
        }
        return quietest(rounds);
    }

    /** How many times a noisy host is given another chance. */
    static final int ROUNDS = 3;

    /**
     * The attempt to believe, out of several.
     *
     * <p>The quietest one, which on a host that was merely interrupted is the
     * right answer and on a host that is genuinely starved is still refused —
     * {@link Calibration#quiet()} decides that, not this. Separate and testable
     * because it is the rule that determines whether somebody's run starts.
     */
    public static Calibration quietest(java.util.List<Calibration> rounds) {
        Calibration best = null;
        for (Calibration c : rounds) {
            if (best == null || c.noise() < best.noise()) best = c;
        }
        return best;
    }

    /** One pass of the fit. {@link #calibrate()} decides how many are needed. */
    private static Calibration measure() {
        double[] targets = {0.05, 0.1, 0.25, 0.5, 1, 2};
        var samples = new java.util.ArrayList<double[]>();
        var targetsNs = new java.util.ArrayList<Double>();
        for (double target : targets) {
            if (target < FIT_FROM_MS) continue;
            int reps = target <= 0.25 ? 200 : 60;
            long nanos = (long) (target * 1e6);
            double[] taken = new double[reps];
            for (int i = 0; i < reps; i++) {
                long t0 = System.nanoTime();
                LockSupport.parkNanos(nanos);
                taken[i] = System.nanoTime() - t0;
            }
            samples.add(taken);
            targetsNs.add(target * 1e6);
        }
        return summarise(samples, targetsNs);
    }

    /**
     * What a set of measured parks says the correction is, and how noisy the host
     * was while they were taken.
     *
     * <p>Separate from the measuring, and public, so the property this whole class
     * depends on can be tested against samples chosen rather than observed. A
     * regression test for the bug that produced it needs to hand this one absurd
     * park among two hundred ordinary ones and see the answer not move — and a
     * test that could only fail on a genuinely overloaded machine would be a test
     * that never ran.
     *
     * @param samples   the parks taken at each target, in nanoseconds
     * @param targetsNs what was asked for at each, in nanoseconds, in the same order
     */
    public static Calibration summarise(java.util.List<double[]> samples,
                                        java.util.List<Double> targetsNs) {
        double sum = 0;
        int n = 0, wild = 0, total = 0;
        for (int t = 0; t < samples.size(); t++) {
            double[] taken = samples.get(t);
            if (taken.length == 0) continue;
            double median = median(taken);
            sum += median / targetsNs.get(t);
            n++;
            for (double v : taken) {
                total++;
                if (v > WILD * median) wild++;
            }
        }
        double c = n == 0 ? 1.0 : sum / n;
        return new Calibration(c < 1.0 ? 1.0 : c,        // a host that never overshoots needs none
                               total == 0 ? 0 : wild / (double) total);
    }

    /**
     * The middle sample, which is what a busy host cannot move.
     *
     * <p>The mean can be moved by one sample: see {@link #calibrate()}.
     */
    public static double median(double[] samples) {
        double[] sorted = samples.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        if (n == 0) return 0;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    /** Just the ratio, for callers that have already decided to proceed. */
    public static double measureCorrection() { return calibrate().correction(); }
}
