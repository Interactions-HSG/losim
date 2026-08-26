import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import losim.api.Takes;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * A worker that blocks on real work, which is not a finding and must not become one.
 *
 * <p>Waiting for something that is happening is most of what a distributed program
 * does, and its length is set by that something's own progress rather than written
 * down — so none of this is a duration k_time should have divided. A verifier that
 * flagged it would fire on nearly every correct concurrent handler, and a report
 * that fires on everything is one people learn to scroll past.
 *
 * <p>The self-unpark and the latch already counted down are so that the fixture
 * returns; what is being tested is the shape of the calls, not the waiting.
 */
public final class Blocker extends WorkerBase {

    @Takes(refMs = 2)
    @Override protected Counts map(Chunk c) {
        var arrived = new CountDownLatch(1);
        arrived.countDown();
        try {
            arrived.await(200, TimeUnit.MILLISECONDS);      // a timeout, not a sleep
            LockSupport.unpark(Thread.currentThread());
            LockSupport.park();                             // a wait, not a sleep
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Counts.newBuilder().putCounts(c.getText().trim(), 1).build();
    }
}
