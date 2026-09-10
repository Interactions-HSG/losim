import io.grpc.Channel;
import dissaly.api.Dissaly;
import dissaly.t.Chunk;
import dissaly.t.Counts;
import dissaly.t.WorkerGrpc;

/**
 * A handler that calls another machine — the shape half a master is made of.
 *
 * <p>It finds its peer by what that peer offers, never by hostname, and it gets a
 * channel from losim rather than building one. What comes back is an ordinary
 * {@code io.grpc.Channel}: the call site below is plain generated-stub gRPC, and it
 * is a real call with latency, byte counts, a span under this handler's span, and
 * everything a simulation does to the machine at the other end.
 */
public final class Forwarder extends WorkerBase {

    @Override protected Counts map(Chunk c) {
        var here = Dissaly.current();
        var workers = here.peersServing("Worker");
        if (workers.isEmpty()) return Counts.newBuilder().putCounts("nobody-to-ask", 1).build();

        Channel to = here.channelTo(workers.get(0));
        here.reveal("forwardedTo", workers.get(0));
        return WorkerGrpc.newBlockingStub(to).map(c);
    }
}
