import static org.junit.jupiter.api.Assertions.*;

import losim.res.Meter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the allocation meter reports when it cannot report.
 *
 * <p>Both of these were live: a machine that lost one of its threads answered
 * "allocated nothing" for the rest of the run, and the figure a cumulative
 * series is plotted from could go backwards. Neither failed loudly, and
 * {@code allocMb} is one of the resources the scaler fits a law against.
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

        // The bug: this answered 0, which a caller cannot tell from a machine
        // that genuinely allocated nothing — and which, once a boot baseline is
        // subtracted, goes negative and clamps to zero for good.
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
}
