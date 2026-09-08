import static org.junit.jupiter.api.Assertions.*;

import io.grpc.BindableService;
import java.util.concurrent.ConcurrentMap;
import losim.api.Losim;
import losim.runtime.Machines;
import losim.time.Clock;
import losim.trace.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one thing two services on a machine may share.
 *
 * <p>Until now they shared nothing: {@code runs:} builds one instance per class
 * and no reference connects them, so a cache one service filled was invisible to
 * the other on the same machine. That is not what a process is — one process holds
 * one index, and every part of it can see it — and the workaround people reach for
 * is a static, which is shared by every machine in the cluster at once and
 * therefore attributes its memory to nobody.
 *
 * <p>So: a map owned by the machine. The three properties worth asserting are the
 * three that make it a machine's rather than a program's — it is one map per
 * machine, it is gone when the machine restarts, and what it holds is charged to
 * the machine that holds it.
 */
class StoreTest {

    private static Machines cluster() {
        return new Machines(new Telemetry(new Clock(1.0, 1.0), Telemetry.Level.FULL));
    }

    @Test
    @DisplayName("every service on a machine reaches the same map")
    void oneMapPerMachine() throws Exception {
        try (var machines = cluster()) {
            var m = machines.machine("m", "m5.large", "z");

            // Two separate arrivals on the machine's own threads, which is what two
            // handlers on two rpcs are.
            m.submit(() -> Losim.current().local().put("index", "built")).get();
            Object seen = m.submit(() -> Losim.current().local().get("index")).get();

            assertEquals("built", seen,
                    "a second service on the same machine could not see what the first left");
        }
    }

    @Test
    @DisplayName("and no other machine reaches it")
    void notSharedBetweenMachines() throws Exception {
        try (var machines = cluster()) {
            var a = machines.machine("a", "m5.large", "z");
            var b = machines.machine("b", "m5.large", "z");

            a.submit(() -> Losim.current().local().put("index", "a's")).get();

            assertNull(b.submit(() -> Losim.current().local().get("index")).get(),
                    "the store crossed a machine boundary, which no process's memory does");
            assertNotSame(a.local(), b.local(), "one map for the whole cluster is a static");
        }
    }

    @Test
    @DisplayName("a restart empties it, because a restart is what losing things means")
    void restartEmptiesIt() throws Exception {
        try (var machines = cluster()) {
            var m = machines.machine("m", "m5.large", "z");
            // Rebuildable: a machine placed by a simulation gets fresh service
            // instances on restart, and the store has to go the same way or a
            // restart would mean two different things in one machine.
            m.serves(Nothing::new, "Nothing", "test:1");

            m.submit(() -> Losim.current().local().put("index", "built")).get();
            assertEquals("built", m.local().get("index"));

            m.kill("under test");
            m.restart();

            assertTrue(m.local().isEmpty(),
                    "the machine came back remembering its cache while its services forgot "
                    + "their fields, so a restart no longer means one thing");
        }
    }

    @Test
    @DisplayName("what it holds is the machine's memory, not nobody's")
    void countsAgainstTheMachine() throws Exception {
        try (var machines = cluster()) {
            var m = machines.machine("m", "m5.large", "z");
            m.serves(Nothing::new, "Nothing", "test:1");

            m.measureRetained();
            long before = m.retainedBytes();

            // Eight megabytes, because the walk reports in bytes and the assertion
            // has to survive whatever the empty machine already holds.
            m.submit(() -> Losim.current().local().put("blob", new byte[8 << 20])).get();
            m.measureRetained();
            long after = m.retainedBytes();

            assertTrue(after - before > 4 << 20,
                    "the store is outside the retained-heap walk, so a machine could hold "
                    + "anything at all and still fit under its memory cap: " + before + " -> " + after);
        }
    }

    @Test
    @DisplayName("the seed is the simulation's, so generated data varies with it")
    void seedComesFromTheScenario() throws Exception {
        try (var machines = new Machines(
                new Telemetry(new Clock(1.0, 1.0), Telemetry.Level.FULL),
                new losim.runtime.Net(7), 7)) {
            var m = machines.machine("m", "m5.large", "z");
            assertEquals(7L, (long) m.submit(() -> Losim.current().seed()).get());
        }
    }

    /** A service that serves nothing, so a machine can be rebuildable without a schema. */
    private static final class Nothing implements BindableService {
        @Override public io.grpc.ServerServiceDefinition bindService() {
            return io.grpc.ServerServiceDefinition.builder("test.Nothing").build();
        }
    }
}
