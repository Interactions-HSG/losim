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
     *
     * <p>Monotonic: a thread asking for its own figure cannot be in the middle
     * of refilling its own TLAB while it asks. {@link #allocatedBy}, which
     * reads other threads, has no such guarantee and can go backwards. A
     * bracket's width is therefore never negative: 77 million self-reads under
     * hard allocation produced no dip and no negative width.
     */
    public static long allocNow() {
        return MX.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    /**
     * Bytes allocated by a set of threads, or <b>−1 if any of them cannot be read</b>.
     *
     * <p>The sentinel marks "cannot be read," distinct from a genuine zero. A
     * terminated thread reads −1, and summing that in with the rest would answer
     * 0 for the whole machine: a caller subtracting a boot baseline from that
     * turns it into a negative, while a caller clamping at zero turns it into
     * "this machine has allocated nothing," permanently and silently. A machine
     * can honestly allocate zero bytes; failing to read its counters is a
     * different condition, and the two need distinct values.
     *
     * <p><b>Not monotonic, and cannot be made so here.</b> Reading another
     * thread's figure sums its retired total and the used part of its current
     * TLAB, without reading the two together. A reader that catches a thread
     * mid-refill sees up to a whole TLAB disappear, and more when it is
     * descheduled across several. Measured: the worst fall equals the TLAB size
     * (0.062 MB at {@code -XX:TLABSize=65536}, 0.999 MB at 1m, none under
     * {@code -XX:-UseTLAB}). Whoever plots this as a counter has to carry a
     * high-water mark, which is what {@code Machine.allocatedBytes()} does.
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
