package losim.time;

import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Fires each fault at an absolute instant, rather than after an interval.
 *
 * <p>{@code ScheduledExecutorService} is not usable for this. It runs late by an
 * amount that itself moves with load — 3.6 ms one run, 10.7 ms the next — so no
 * fixed correction fits it, and a scenario that says "kill the reducer at 900 ms"
 * lands somewhere else every time.
 *
 * <p>One thread, a queue ordered by absolute {@link System#nanoTime()}, parking
 * coarsely and spinning the last couple of milliseconds. That is about three
 * thousand times more accurate at the median.
 *
 * <h2>Two details, both load-bearing</h2>
 * The park must be <b>calibrated</b> (D5) or it sails straight past the spin
 * window it was meant to stop short of, which throws away the entire benefit. And
 * the thread wants {@link Thread#MAX_PRIORITY}, because it is competing with
 * every machine in the fleet for a core.
 *
 * <p>What remains is the OS, not the design: with every core saturated the
 * dispatcher is descheduled, so placement is around 3 µs at the median and around
 * 8 ms at the 99th percentile. Nothing in userspace fixes that, and a scenario
 * whose lesson depends on a tighter margin than that has to say so.
 */
public final class Dispatcher implements AutoCloseable {

    /** The last stretch is spun rather than slept, because no timer is this fine. */
    private static final long SPIN_NS = 2_000_000;

    private record Due(long atNs, long seq, Runnable action) {}

    private final PriorityQueue<Due> queue = new PriorityQueue<>(
            (a, b) -> a.atNs != b.atNs ? Long.compare(a.atNs, b.atNs) : Long.compare(a.seq, b.seq));
    private final AtomicLong seq = new AtomicLong();
    private final Clock clock;
    private final Thread thread;
    private volatile boolean running = true;

    public Dispatcher(Clock clock) {
        this.clock = clock;
        this.thread = new Thread(this::loop, "losim-faults");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
    }

    /** Schedules an action for a simulated instant, in reference milliseconds. */
    public synchronized void at(double refMs, Runnable action) {
        long atNs = (long) (refMs / clock.kTime() * 1e6);
        queue.add(new Due(atNs, seq.getAndIncrement(), action));
    }

    public void start() { thread.start(); }

    public synchronized int pending() { return queue.size(); }

    private void loop() {
        while (running) {
            Due next;
            synchronized (this) { next = queue.poll(); }
            if (next == null) {
                LockSupport.parkNanos(200_000);
                continue;
            }
            for (;;) {
                long left = next.atNs() - clock.elapsedNs();
                if (left <= 0) break;
                if (left > SPIN_NS) clock.parkRealNanos(left - SPIN_NS);
                else Thread.onSpinWait();
            }
            try { next.action().run(); }
            catch (RuntimeException ignored) { }        // one bad fault must not stop the rest
        }
    }

    @Override public void close() {
        running = false;
        thread.interrupt();
    }
}
