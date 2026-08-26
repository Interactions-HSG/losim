import losim.api.Takes;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * A worker holding another machine's service directly.
 *
 * <p>The sharpest form of shared state, and a modelling mistake rather than a
 * measurement one: gRPC is the only way machines talk, and a field is not a network.
 * A call through this one crosses no wire, waits out no latency, costs no bytes,
 * survives a partition, and keeps working after the machine at the other end is killed.
 */
public final class Referrer extends WorkerBase {

    static Counter peer;

    /** How the two ends find each other: a field, rather than a name and a channel. */
    static void wire(Counter other) { peer = other; }

    @Takes(refMs = 2)
    @Override protected Counts map(Chunk c) {
        return peer == null ? Counts.getDefaultInstance() : peer.map(c);
    }
}
