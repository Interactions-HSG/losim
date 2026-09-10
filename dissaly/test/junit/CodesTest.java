import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import dissaly.sim.Codes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * dissaly's copy of gRPC's status codes, held against gRPC's own.
 *
 * <p>The copy exists because the loader has to refuse {@code status: UNAVALABLE}
 * on the line it is written on, and the loader also runs inside the console's
 * server — which is started with {@code dissaly.jar} alone, so reading
 * {@code io.grpc.Status.Code} there is a {@code NoClassDefFoundError} the moment
 * somebody presses save.
 *
 * <p>A second copy of somebody else's enum is a thing that drifts. This is the
 * whole reason it is safe to have one: the test classpath has gRPC on it, so the
 * two can be compared, and a code added or renamed upstream fails here rather
 * than becoming a simulation dissaly quietly refuses to load.
 */
class CodesTest {

    @Test
    @DisplayName("every gRPC status code but OK, in gRPC's own order")
    void theSameCodes() {
        List<String> theirs = java.util.Arrays.stream(io.grpc.Status.Code.values())
                .map(Enum::name)
                .filter(n -> !n.equals("OK"))
                .toList();
        assertEquals(theirs, Codes.ALL,
                "dissaly.sim.Codes is a copy of io.grpc.Status.Code, and they have parted ways."
                + " Update the copy: the loader reads it where gRPC is not on the classpath.");
    }

    @Test
    @DisplayName("OK is not one of them, because a call that worked is not a failure")
    void notOk() {
        assertFalse(Codes.known("OK"));
        // Run backwards: a list that knew nothing would pass the line above.
        assertTrue(Codes.known("UNAVAILABLE"));
        assertFalse(Codes.known("UNAVALABLE"), "a typo is not a code");
    }
}
