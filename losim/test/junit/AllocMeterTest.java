import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import losim.res.Meter;
import losim.runtime.Fleet;
import losim.time.Clock;
import losim.trace.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the allocation meter reports when it cannot report.
 *
 * <p>A machine that has lost one of its threads must not answer "allocated
 * nothing" for the rest of the run, and the figure a cumulative series is
 * plotted from must never go backwards. Neither failure is loud on its own,
 * and {@code allocMb} is one of the resources the scaler fits a law against.
 */
class AllocMeterTest {

    @Test
    @DisplayName("a set holding a terminated thread reads unknown, not zero")
    void terminatedThreadIsUnknownRatherThanNothing() throws Exception {
        Thread live = Thread.currentThread();
        Thread gone = new Thread(() -> {
            long n = 0;
            for (int i = 0; i < 20_000; i++) n += new byte[64].length;
            assertTrue(n > 0);
        });
        gone.start();
        gone.join();

        long alone = Meter.allocatedBy(new long[]{ live.threadId() });
        assertTrue(alone > 0, "a live thread has allocated something");

        // 0 here would be indistinguishable from a machine that genuinely
        // allocated nothing, and, once a boot baseline is subtracted, would go
        // negative and clamp to zero for good — so a set holding an unreadable
        // thread must answer -1 instead.
        long withDead = Meter.allocatedBy(new long[]{ live.threadId(), gone.threadId() });
        assertEquals(-1, withDead,
                "a set with an unreadable thread must say it does not know");
        assertNotEquals(0, withDead, "0 is a number a machine can honestly have");
    }

    @Test
    @DisplayName("a readable set still sums, so the sentinel did not swallow the ordinary case")
    void readableSetStillSums() {
        long one = Meter.allocatedBy(new long[]{ Thread.currentThread().threadId() });
        assertTrue(one > 0);
        long twice = Meter.allocatedBy(new long[]{
                Thread.currentThread().threadId(), Thread.currentThread().threadId() });
        assertTrue(twice >= one, "the same thread counted twice is not less than once");
    }

    /**
     * The invariant, not the defect. Asserting that the JVM's counter <i>does</i>
     * fall would fail on a JVM that had fixed it, which is not a regression in
     * losim; what losim promises is that its own figure never does. The message
     * carries how many raw falls the run actually caught, so a green result still
     * says whether it exercised the case or merely failed to provoke it.
     */
    @Test
    @DisplayName("a machine's reported allocation never falls, however the raw counter behaves")
    void reportedAllocationIsMonotonic() throws Exception {
        var tel = new Telemetry(new Clock(1.0, 1.0), Telemetry.Level.OFF);
        try (var fleet = new Fleet(tel)) {
            var m = fleet.machine("m", "m5.large", "z");
            var stop = new AtomicBoolean();
            // On the machine's own pool threads, which are the ones it meters.
            var burning = m.submit(() -> {
                Object sink = null;
                for (int i = 0; !stop.get(); i++) {
                    sink = (i % 512 == 0) ? new byte[1 << 20] : new byte[1 << 8];
                    if (sink.hashCode() == 42) fail("unreachable, and keeps the sink live");
                }
            });

            long rawFalls = 0, prevRaw = -1, prevReported = -1;
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (System.nanoTime() < deadline) {
                long raw = m.rawAllocatedBytes();
                long reported = m.allocatedBytes();
                if (prevRaw >= 0 && raw < prevRaw) rawFalls++;
                assertTrue(reported >= prevReported,
                        "reported allocation fell from " + prevReported + " to " + reported);
                prevRaw = raw;
                prevReported = reported;
            }
            stop.set(true);
            burning.get();
            // Not an assertion: a run that provoked none has proven nothing, and
            // saying so is better than a green tick that implies otherwise.
            System.out.println("  (raw counter fell " + rawFalls + " times during this run)");
        }
    }
}
