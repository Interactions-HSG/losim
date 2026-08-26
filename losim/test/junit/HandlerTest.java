import static org.junit.jupiter.api.Assertions.*;

import losim.api.Losim;
import losim.t.Chunk;
import losim.t.Counts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A gRPC handler, debugged on its own, in plain JUnit, with nothing simulating
 * anything.
 *
 * <p>This is the test that decides whether the service shape was worth its twelve
 * lines of adapter. There is no fleet here, no scenario, no interceptor and no
 * clock — just a class, constructed, with a method called on it. Set a breakpoint
 * on the line below and step into {@code map}: it is ordinary Java, and stopping
 * on it stops nothing else, because nothing else is running.
 *
 * <p>losim is on the classpath because {@code @Cost} is a compile-time annotation
 * and {@code Losim.current()} has to resolve. It does nothing here, which is the
 * point: a handler that needed a simulation to be testable would not be testable.
 */
class HandlerTest {

    @Test
    @DisplayName("a handler is an ordinary object with an ordinary method")
    void countsWords() {
        var counter = new Counter();

        Counts counts = counter.map(Chunk.newBuilder()
                .setText("the cat sat on the mat").setLines(1).build());

        assertEquals(2, counts.getCountsOrDefault("the", 0));
        assertEquals(1, counts.getCountsOrDefault("cat", 0));
        assertEquals(5, counts.getCountsCount());
    }

    @Test
    @DisplayName("nothing is running, and the handler is not told otherwise")
    void recordingIsSilentOutsideARun() {
        assertFalse(Losim.current().isRunning());
        // A test does not have to know losim exists for a handler to be callable.
        assertDoesNotThrow(() -> Losim.current().reveal("emitted", 3));
        assertDoesNotThrow(() -> Losim.current().log("counted"));
        assertDoesNotThrow(() -> Losim.current().records(1));
        assertDoesNotThrow(() -> Losim.current().wroteDisk(4096));
    }

    @Test
    @DisplayName("but asking about a world that is not there fails, rather than inventing one")
    void stateThrowsOutsideARun() {
        var e = assertThrows(IllegalStateException.class, () -> Losim.current().machine());
        assertTrue(e.getMessage().contains("no simulation is running"));
        assertThrows(IllegalStateException.class, () -> Losim.current().peers());
        assertThrows(IllegalStateException.class, () -> Losim.current().clockMs());
        // A fabricated empty fleet would let this test pass while asserting nothing,
        // which is worse than failing.
    }

    @Test
    @DisplayName("the reducer accumulates, which is the whole reason it can run out of memory")
    void reduceAccumulates() {
        var counter = new Counter();

        counter.reduce(Counts.newBuilder().putCounts("a", 1).build());
        Counts second = counter.reduce(Counts.newBuilder().putCounts("a", 2).putCounts("b", 1).build());

        assertEquals(3, second.getCountsOrDefault("a", 0));
        assertEquals(1, second.getCountsOrDefault("b", 0));
    }
}
