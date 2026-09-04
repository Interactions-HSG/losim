package losim.res;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

/**
 * What losim's own work costs, on the thread it costs it on.
 *
 * losim runs on the machine's own threads. Opening a span, rendering an
 * argument, accounting a cost — each allocates and each takes wall clock, on the
 * very threads whose allocation and duration are the measurement. The counters
 * cannot tell losim's bytes from the program's, so losim has to (D13).
 *
 * <p>The overhead is roughly constant <i>per call</i> rather than proportional
 * to the data in it, so in {@code demand = c + a*n^b} it lands in {@code c} —
 * and the probe grid runs at small {@code n}, where a fixed term is
 * proportionally largest. Unmeasured, it inflates {@code c}, drags the fitted
 * exponent, and the distortion is then extrapolated to full scale.
 */
public final class Meter {
    private Meter() {}

    private static final ThreadMXBean MX = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /**
     * Bytes this thread has allocated so far.
     *
     * <p>Paired around a region of losim's own work, and deliberately <b>not</b>
     * wrapped in a helper taking a lambda: constructing the lambda would allocate
     * against the machine before the first mark is even read.
     *
     * <p>Returns −1 for a virtual thread, which is why platform threads are a
     * requirement rather than a preference (D12).
     */
    public static long allocNow() {
        return MX.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    /**
     * Bytes allocated by a set of threads, or <b>−1 if any of them cannot be read</b>.
     *
     * <p>Unknown rather than zero, which is the whole point of the sentinel. A
     * terminated thread reads −1, and this used to answer 0 for the entire
     * machine when one of its own had gone — which a caller subtracting a boot
     * baseline from turns into a negative, and a caller clamping at zero turns
     * into "this machine has allocated nothing", permanently and silently. Zero
     * is a number a machine can honestly have; not knowing is not, and the two
     * must not arrive looking the same.
     */
    public static long allocatedBy(long[] threadIds) {
        long[] each = MX.getThreadAllocatedBytes(threadIds);
        long sum = 0;
        for (long b : each) { if (b < 0) return -1; sum += b; }
        return sum;
    }

    /**
     * What one bracket costs that the bracket itself cannot see.
     *
     * <p>A bracket reads the clock and the allocation counter twice, and the
     * first read of each pair happens before there is anything to charge it to.
     * Measured once per JVM — around 70 ns on the reference laptop — and charged
     * back per metered stop, so losim pays for its own instrumentation rather
     * than the machine. Without it losim bills a machine <i>more</i> the more
     * heavily its program is instrumented, which is exactly backwards.
     */
    public static final long UNSEEN_NANOS_PER_REGION = calibrate();

    private static long calibrate() {
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            final int n = 50_000;
            long t0 = System.nanoTime(), metered = 0;
            for (int i = 0; i < n; i++) {
                long b0 = allocNow(), n0 = System.nanoTime();
                metered += (System.nanoTime() - n0) + (allocNow() - b0) * 0;
            }
            long unseen = (System.nanoTime() - t0) / n - metered / n;
            best = Math.min(best, Math.max(0, unseen));
        }
        return best;
    }
}
