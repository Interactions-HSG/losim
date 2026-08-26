import losim.api.Cost;
import losim.t.Ping;

/**
 * A handler whose duration is declared rather than measured.
 *
 * <p>Both methods are annotated, and that is not redundant: {@code @Cost} is found
 * by the name grpc-java generates, so an rpc whose Java method is inherited and
 * unannotated costs nothing at all. Which is the right default — an uncosted
 * handler should run at whatever speed it runs — and an easy thing to trip over.
 */
public final class Slow extends VolleyBase {

    /** Big enough that the host's own jitter is a rounding error on it. */
    @Cost(refMs = 200)
    @Override protected void hit(Ping p) { }

    @Cost(refMs = 200)
    @Override protected void poll(Ping p) { }
}
