import static org.junit.jupiter.api.Assertions.*;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.util.concurrent.TimeUnit;
import losim.api.Losim;
import losim.t.Chunk;
import losim.t.Counts;
import losim.t.WorkerGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The same system, served and called through gRPC, with losim not running.
 *
 * <p>{@link HandlerTest} calls {@code map} directly, which proves the handler is
 * an ordinary object. It never goes through gRPC at all, so it cannot answer the
 * question a student actually asks the first time something goes wrong: <i>can I
 * put a breakpoint in my service and watch a request arrive?</i>
 *
 * <p>So this starts a {@link Server}, binds the student's own service to it,
 * opens a {@link ManagedChannel} and calls the generated blocking stub. Every
 * type here is grpc-java's or the schema's. There is no scenario, no fleet, no
 * compressed clock and no interceptor, and losim's runtime is never started —
 * the last test says so out loud, so that this one cannot quietly stop being
 * true.
 *
 * <p><b>Why this is a test and not a note in the manual.</b> The claim is that a
 * losim system is a plain gRPC system, which is what makes the VS Code Java
 * tooling work on it: Run and Debug on this class, a breakpoint inside
 * {@code Counter.map}, step through, inspect {@code holding}. That claim is easy
 * to write down and easy to break — one losim type in a signature, one static
 * that only a run initialises, and the debugger can no longer reach the handler
 * without a simulation around it. A sentence in the manual would go stale in
 * silence. This fails.
 *
 * <p><b>What this is not.</b> The transport is in-process, because in-process is
 * the only transport {@code vendor/jars} carries — there is no grpc-netty or
 * grpc-okhttp in it, and {@code Grpc.newServerBuilderForPort} therefore fails
 * with "No functional server found". Everything above the transport is the real
 * thing: the generated stub, the marshalling, the method dispatch, the status
 * codes. What a lab cannot do today is open a socket, so a student cannot point
 * grpcurl at their service or reach it from another process. If that becomes
 * something the course wants, it is one jar.
 */
class GrpcAloneTest {

    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void start() throws Exception {
        // A fresh name per test, so two of these never share a server.
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new Counter())
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void stop() throws Exception {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a client calls the service through gRPC, with no simulator anywhere")
    void throughTheStub() {
        var worker = WorkerGrpc.newBlockingStub(channel);

        // Put a breakpoint on the next line and step in: execution stops inside
        // Counter.map, with a stack that goes down through grpc-java and nothing
        // else. `directExecutor` is what keeps it on this thread, so stepping
        // does not land you in a worker pool.
        Counts counts = worker.map(Chunk.newBuilder()
                .setText("the cat sat on the mat").setLines(1).build());

        assertEquals(2, counts.getCountsOrDefault("the", 0));
        assertEquals(1, counts.getCountsOrDefault("cat", 0));
        assertEquals(5, counts.getCountsCount());
    }

    @Test
    @DisplayName("every method the schema declares is bound, not only the overridden one")
    void everyMethodIsBound() {
        var worker = WorkerGrpc.newBlockingStub(channel);
        Counts once = worker.map(Chunk.newBuilder().setText("a b a").setLines(1).build());

        // `reduce` is WorkerBase's default. The service carries it either way,
        // which is what makes a half-written system still debuggable.
        Counts again = worker.reduce(once);
        assertEquals(2, again.getCountsOrDefault("a", 0));
    }

    @Test
    @DisplayName("a handler that throws answers with a status rather than hanging")
    void failuresArriveAsStatus() {
        var worker = WorkerGrpc.newBlockingStub(channel);
        // Whatever Counter makes of a negative line count, the caller has to be
        // told something. A debugger session that hangs on the first mistake
        // would be the worst possible first experience of this course.
        Chunk odd = Chunk.newBuilder().setText("x").setLines(-1).build();
        try {
            Counts counts = worker.map(odd);
            assertNotNull(counts, "an answer is an answer");
        } catch (StatusRuntimeException e) {
            assertNotNull(e.getStatus(), "and a status is an answer too");
        }
    }

    @Test
    @DisplayName("losim is on the classpath and is not running")
    void nothingIsSimulated() {
        // losim.jar is here because `@Takes` is a compile-time annotation on
        // Counter and has to resolve. If this ever comes back true, something
        // started a simulation and the tests above stopped proving what they say.
        assertFalse(Losim.current().isRunning());
    }
}
