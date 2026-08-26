package losim.api;

import java.util.List;

/**
 * What a program is allowed to say to losim, and to ask it, from inside a handler.
 *
 * There are exactly two implementations, and the difference between them is the
 * point. Inside a run, calls reach the machine that is serving. Outside one — in
 * a plain unit test, where losim is on the classpath because {@link Cost} is a
 * compile-time annotation but nothing is simulating anything — the recording
 * calls are silent and the state calls throw.
 *
 * <p>That asymmetry is deliberate. A silent {@code reveal} lets a handler be
 * called directly from a test without the test having to know losim exists. A
 * fabricated empty fleet would let the same test pass while asserting nothing,
 * which is worse than failing.
 *
 * <h2>Recording — silent outside a run</h2>
 * {@link #reveal}, {@link #log}, {@link #records}
 *
 * <h2>State — throws outside a run</h2>
 * {@link #machine}, {@link #peers}, {@link #peersServing}, {@link #clockMs}
 *
 * <p><b>Every method on this interface meters its own body.</b> These run inside
 * the handler, on the machine's own thread, between the two marks that measure
 * that handler — so what losim spends here is charged to losim and taken back off
 * what the machine reports (D13). The boundary is exact: in
 * {@code reveal("keys", map.size())} the {@code size()} is the program's cost and
 * stays with the program; everything after the argument is evaluated is losim's.
 */
public interface LosimCtx {

    /** Whether a simulation is running. False in a bare unit test. */
    boolean isRunning();

    // ---------------------------------------------------------------- recording

    /**
     * Records a named value from inside a handler, so a reader of the trace can
     * see what the computation was doing and not merely that it happened.
     *
     * <p>The primitive overloads are not a convenience. {@code reveal(String,
     * Object)} boxes an {@code int} at the <i>call site</i> — before losim's
     * accounting can open — so the box is charged to the program though it exists
     * only because the parameter is an {@code Object}. At one call per handler
     * that is invisible; at a thousand it bends the fitted memory exponent by
     * 0.04, and the exponent is the one number that must not bend. Taking the
     * primitive moves the boxing inside the bracket, where it belongs to losim.
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
     * How many records this call processed.
     *
     * <p>Two things need it. {@link Cost#refNsPerRecord()} is charged against it,
     * and the scaler engine needs to know which independent variable a cost site
     * is a function of — records, or distinct keys, or bytes — because fitting a
     * resource against the wrong variable gives an exponent that will not survive
     * a change of corpus.
     */
    void records(long n);

    /**
     * Records that this call wrote bytes to the machine's disk.
     *
     * <p>Not a recording call, and it does not behave like one: a machine whose
     * disk is full cannot take the write, so this <b>throws</b> when the cap is
     * exceeded, and the handler fails the way it would have failed for real.
     * Recording the write and carrying on would let a design that cannot fit on
     * its disks appear to work.
     *
     * <p>Silent outside a run, like the recording calls — a unit test has no disk
     * to fill.
     */
    void wroteDisk(long bytes);

    // -------------------------------------------------------------------- state

    /** The name of the machine serving this call. */
    String machine();

    /** Every other machine in the fleet, by name. */
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
     * Simulated milliseconds since the run began — the clock the scenario was
     * written against, not wall clock. Reading {@code System.nanoTime()} instead
     * reports the compressed clock as though it were the simulated one, which is
     * why the verifier flags it (D11).
     */
    double clockMs();
}
