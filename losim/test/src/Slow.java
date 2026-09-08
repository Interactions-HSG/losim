import losim.t.Ping;

/**
 * A handler whose duration is declared rather than measured.
 *
 * <p>Both rpcs are priced by the simulation, and that is not redundant: a cost is
 * looked up per rpc, so one the simulation leaves out costs nothing at all. Which is
 * the right default — an unpriced handler should run at whatever speed it runs —
 * and an easy thing to trip over.
 */
public final class Slow extends VolleyBase {

    /** Big enough that the host's own jitter is a rounding error on it. */
    @Override protected void hit(Ping p) { }

    @Override protected void poll(Ping p) { }
}
