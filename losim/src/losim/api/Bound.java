package losim.api;

import io.grpc.Channel;
import java.util.List;

/**
 * The node serving the current call, as exposed through the API facade.
 *
 * {@code losim.api} declares this interface and {@code losim.runtime} implements
 * it. The facade has no runtime dependency, so handlers can compile and run unit
 * tests against the API alone.
 *
 * <p>This is an internal API; student code uses {@link Losim}.
 */
public interface Bound {

    String name();

    /**
     * The node's fixed capacity, cached from its instance specification.
     */
    Spec here();

    List<String> peers();

    List<String> peersServing(String service);

    /** Simulated milliseconds since the run began. */
    double clockMs();

    /** The simulation's seed. */
    long seed();

    /** The node's store, shared by its services. */
    java.util.concurrent.ConcurrentMap<String, Object> local();

    /**
     * A channel to a peer, created once and owned by the node.
     *
     * <p>The channel is cached so handlers do not manage its lifetime.
     */
    Channel dial(String peer);

    /** Records one event against this machine. */
    void event(String kind, Object... kv);

    /** Declares how many units the call in flight processed. */
    void units(long n);

    /** Records a write and throws if the disk cap would be exceeded. */
    void wroteDisk(long bytes);

    /**
     * Spends a declared duration against the compressed clock.
     *
     * <p>This is program time. Callers should keep it outside regions metered as
     * losim's own work.
     */
    void sleep(double refMs);

    /**
     * Charges losim's own work to the node ledger and enclosing span. The charge is
     * excluded from the handler's allocation and duration.
     */
    void charge(long bytes, long nanos);
}
