import io.grpc.Channel;
import losim.api.Losim;
import losim.api.Takes;
import losim.t.Chunk;
import losim.t.Counts;
import losim.t.WorkerGrpc;

/**
 * A handler that calls another machine — the shape half a coordinator is made of.
 *
 * <p>It finds its peer by what that peer offers, never by hostname, and it gets a
 * channel from losim rather than building one. What comes back is an ordinary
 * {@code io.grpc.Channel}: the call site below is plain generated-stub gRPC, and it
 * is a real call with latency, byte counts, a span under this handler's span, and
 * everything a scenario does to the machine at the other end.
 */
public final class Forwarder extends WorkerBase {

    @Takes(refMs = 1)
    @Override protected Counts map(Chunk c) {
        var here = Losim.current();
        var workers = here.peersServing("Worker");
        if (workers.isEmpty()) return Counts.newBuilder().putCounts("nobody-to-ask", 1).build();

        Channel to = here.channelTo(workers.get(0));
        here.reveal("forwardedTo", workers.get(0));
        return WorkerGrpc.newBlockingStub(to).map(c);
    }
}
