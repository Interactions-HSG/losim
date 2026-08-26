package losim.api;

import io.grpc.Channel;
import java.util.List;
import java.util.function.Supplier;

/**
 * The fleet, as the job sees it.
 *
 * <p>Deliberately narrow. A job can find peers, talk to them, telemetrize work it
 * does itself, and say what the answer was. It cannot reach into another machine's
 * memory, start a thread nothing accounts for, or ask whether a peer is alive —
 * that last one because no real network offers it, and a design that depends on
 * knowing is a design that will not survive contact with one.
 */
public interface Cluster {

    /** Every machine in the fleet, by name. */
    List<String> machines();

    /**
     * The machines serving a given service, by name.
     *
     * <p>Peers are found by what they offer, never by hostname. Dead ones are not
     * listed — which is discovery working, not a liveness check: a machine that has
     * gone is a machine that has stopped answering, and finding that out still costs
     * a call and a deadline.
     */
    List<String> serving(String service);

    /** A channel to a peer. Build whatever stub the {@code .proto} generated on top of it. */
    Channel channelTo(String machine);

    /** Simulated milliseconds since the run began — the clock the scenario is written in. */
    double clockMs();

    /**
     * How long the scenario said this run should take, in reference milliseconds.
     *
     * <p>For a job whose shape is "keep going until the run is over" — a load
     * generator, a poller — rather than "do this much work". Without it such a job
     * has to guess, and a job that guesses short finishes before the scenario's
     * weather has happened to it.
     */
    double expectedRunMs();

    /** A line of narration in the trace. */
    void log(String message);

    /**
     * Work the job does itself, with no RPC to carry it.
     *
     * <p>Without this a coordinator merging locally is indistinguishable from one
     * doing nothing: no span covers it and the busiest stretch of a run reads as
     * idle.
     */
    <T> T compute(String label, Supplier<T> body);

    /**
     * Names a stretch of the job, so the trace contains something larger than a call.
     *
     * <pre>{@code
     * try (var p = cluster.phase("map")) {
     *     ...
     *     p.note("chunks", 6);
     * }
     * }</pre>
     *
     * <p>Unlike {@link #compute}, a phase does not mark the machine busy: a
     * coordinator waiting on six workers is not using a core, and reporting that it
     * is would make every occupancy figure in the run a lie.
     */
    Phase phase(String label);

    /** A named stretch of the job. Closing it closes the span. */
    interface Phase extends AutoCloseable {
        /** Something worth knowing about this stretch, recorded on its span. */
        Phase note(String key, Object value);
        @Override void close();
    }

    /** The job's answer, recorded structurally rather than as a line of text. */
    void done(Object answer);
}
