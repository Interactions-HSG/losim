import io.grpc.inprocess.InProcessChannelBuilder;
import losim.api.Cost;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * A worker that opens its own channel.
 *
 * <p>losim is on both sides of every call as gRPC's own interceptors, which is where
 * latency, loss, partitions, declared cost, spans and byte counts all come from. A
 * channel built by hand has none of them attached: the call happens at full speed,
 * survives a partition, and leaves no trace that it happened at all.
 */
public final class Dialer extends WorkerBase {

    @Cost(refMs = 2)
    @Override protected Counts map(Chunk c) {
        var channel = InProcessChannelBuilder.forName("somewhere-else").build();
        channel.shutdownNow();
        return Counts.newBuilder().putCounts("dialled", 1).build();
    }
}
