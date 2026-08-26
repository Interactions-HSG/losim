import java.util.concurrent.atomic.AtomicInteger;
import losim.api.Takes;
import losim.api.Losim;
import losim.t.Ping;

/**
 * A service that counts how many times it was actually called.
 *
 * <p>{@code Hit} declares no idempotency and {@code Poll} declares it, which is the
 * whole difference the retry gate exists to notice.
 */
public final class Pinger extends VolleyBase {

    /** Static on purpose: a restarted machine gets a fresh instance, and the count must outlive it. */
    public static final AtomicInteger HITS = new AtomicInteger();
    public static volatile int failFirst = 0;

    @Takes(refMs = 1)
    @Override protected void hit(Ping p) {
        int n = HITS.incrementAndGet();
        Losim.current().reveal("seq", p.getSeq());
        if (n <= failFirst)
            throw new IllegalStateException("not this time (attempt " + n + ")");
    }
}
