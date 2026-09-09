package losim.api;

import io.grpc.Channel;
import java.util.List;

/**
 * Operations available to a handler through losim.
 *
 * The running context forwards calls to the serving node. The test context keeps
 * recording calls silent and throws for state calls.
 *
 * <p>A silent {@code reveal} lets a handler run in a unit test without a simulator.
 * State calls throw instead of returning an invented cluster or clock.
 *
 * <h2>Recording, silent outside a run</h2>
 * {@link #reveal}, {@link #log}, {@link #units}
 *
 * <h2>State, throws outside a run</h2>
 * {@link #machine}, {@link #here}, {@link #peers}, {@link #peersServing},
 * {@link #clockMs}
 *
 * <p>Each method meters its own body. These calls run inside the handler span, so
 * losim's allocation and duration are charged to losim and excluded from the
 * program's measurements (D13). In {@code reveal("keys", map.size())}, evaluating
 * {@code size()} remains program work; boxing and recording are losim work.
 */
public interface LosimCtx {

    /** Whether a simulation is running. False in a bare unit test. */
    boolean isRunning();

    // ---------------------------------------------------------------- recording

    /**
     * Records a named value from inside a handler, so a reader of the trace can
     * see what the computation was doing and not merely that it happened.
     *
     * <p>Primitive overloads keep boxing inside losim's accounting bracket. The
     * {@code Object} overload would box an {@code int} at the call site and charge
     * that allocation to the program.
     */
    void reveal(String key, int value);
    void reveal(String key, long value);
    void reveal(String key, double value);
    void reveal(String key, boolean value);
    void reveal(String key, String value);

    /** For anything that is not a primitive. Prefer an overload above where one fits. */
    void reveal(String key, Object value);

    /** A line of narration from inside a handler. */
    void log(String message);

    /**
     * How many units this call processed.
     *
     * <p>The simulation charges the per-unit duration against this count. The
     * scaling engine also uses it as the independent variable for fitting costs.
     */
    void units(long n);

    /**
     * Records that this call wrote bytes to the machine's disk.
     *
     * <p>This method throws when the disk cap is exceeded, so the handler fails at
     * the same point as it would on a full disk.
     *
     * <p>Outside a run it does nothing, like the recording calls.
     */
    void wroteDisk(long bytes);

    // --------------------------------------------------------------------- time

    /**
     * Waits, for a duration written in reference-machine time.
     *
     * <p>{@code sleep(200)} waits 200 reference milliseconds divided by
     * {@code k_time}. {@code Thread.sleep(200)} waits 200 host milliseconds and is
     * independent of the simulation clock, so the verifier flags it (D11).
     *
     * <p>Waiting does not consume a vCPU and is not multiplied by node degradation.
     * Declared work has both properties.
     *
     * <p>Use {@code simulatedDuration:} for fixed RPC work. Use this method for a
     * duration known only at runtime, such as a backoff, poll interval, or lease.
     *
     * <p>Outside a run it returns immediately.
     */
    void sleep(double refMs);

    // -------------------------------------------------------------------- state

    /** The name of the node serving this call. */
    String node();

    /**
     * What the machine serving this call is made of: its instance type, its zone,
     * its cores and its caps.
     *
     * <p>Local knowledge only — this machine's, never a peer's. A real process can
     * read its own limits and its own instance metadata without a network, and
     * cannot read anyone else's at all. An orchestrator that wants to send small
     * work to small machines therefore has to <b>ask</b> them, which is why a
     * resource manager has a registration call and why that call is worth writing.
     *
     * <p>Free to call: every field is final on the machine and the record is built
     * once, before it boots. Nothing here is measured.
     */
    Spec here();

    /**
     * The simulation's seed.
     *
     * <p>For a handler that generates rather than reads its data. It has to come
     * from here: a workload drawn from a constant of its own is a workload whose
     * every run is the same afternoon, and a sweep of twenty seeds is meant to vary
     * the data as well as the weather.
     */
    long seed();

    /**
     * This machine's own store, shared by every service running on it.
     *
     * <p>Two services on one machine are two objects with no reference between
     * them, which is not what a real process is: one process holds one cache, one
     * index, one connection pool, and every part of it can see them. This is that,
     * and nothing more — a map owned by the machine, reachable from no other
     * machine, and <b>emptied when the machine restarts</b>, because a restart
     * losing what it was holding is the whole reason a restart is interesting.
     *
     * <p>What it holds is walked by the retained-heap measurement like anything a
     * service holds in a field, so it counts against the machine's memory cap. What
     * it costs to read is the program's, not losim's: a lookup here is a hash
     * lookup, of the same order as the field access it replaces, and bracketing it
     * to hand back the nanoseconds was measured at 76 ns spent to hide 10.
     *
     * <p>Not a way around the pool. Work still runs on the machine's own threads;
     * this is only how two services on the same machine stop being strangers.
     */
    java.util.concurrent.ConcurrentMap<String, Object> local();

    /** Every other machine in the cluster, by name. */
    List<String> peers();

    /**
     * The machines serving a given gRPC service, by name — peers are found by
     * what they offer, never by hostname.
     *
     * @param service the service's bare name as written in the {@code .proto},
     *                e.g. {@code "Worker"}
     */
    List<String> peersServing(String service);

    /**
     * A channel to another machine, with losim on it.
     *
     * <p>The other half of {@link #peersServing(String)}: peers are found by what
     * they offer, and this is how one is called. What comes back is an ordinary
     * {@code io.grpc.Channel} — hand it to a generated stub and the call site is
     * plain gRPC, unchanged.
     *
     * <p>What it is not is a channel of your own. losim rides on gRPC's own
     * interceptors, and a channel built by hand has none of them attached: the call
     * happens at full speed, costs no bytes, waits out no latency, survives a
     * partition, and leaves nothing in the trace to say it happened. That is why the
     * verifier flags {@code ManagedChannelBuilder} and this exists instead.
     *
     * <p>Throws outside a run, like the other state calls — a fabricated peer would
     * make a passing test meaningless.
     */
    Channel channelTo(String machine);

    /**
     * Simulated milliseconds since the run began — the clock the simulation was
     * written against, not wall clock. Reading {@code System.nanoTime()} instead
     * reports the compressed clock as though it were the simulated one, which is
     * why the verifier flags it (D11).
     */
    double clockMs();
}
