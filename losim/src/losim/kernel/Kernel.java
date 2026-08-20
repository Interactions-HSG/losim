package losim.kernel;

import losim.trace.Trace;
import losim.trace.TraceEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.Semaphore;

/**
 * The discrete-event kernel. Single-threaded, virtual clock, strict handoff.
 *
 * Invariant, asserted every slice: exactly one thread is runnable. The kernel is
 * blocked whenever a task runs, and every task is parked whenever the kernel runs.
 */
public final class Kernel {

    private long now = 0;
    private long seq = 0;
    private final PriorityQueue<Event> queue = new PriorityQueue<>();
    private final Random rng;
    private final Trace trace = new Trace();
    private final Semaphore kernelPermit = new Semaphore(0);
    private final List<Task> tasks = new ArrayList<>();

    private Task running;                 // the one task currently holding control
    private long runUntil = Long.MAX_VALUE;
    private boolean stopped;
    private Throwable failure;
    private int slices;

    public Kernel(long seed) { this.rng = new Random(seed); }

    public long now() { return now; }
    public Trace trace() { return trace; }
    public Random rng() { return rng; }
    public int slices() { return slices; }
    public Task running() { return running; }

    private java.util.function.Consumer<Task> onTaskFinished = t -> {};
    public void onTaskFinished(java.util.function.Consumer<Task> c) { this.onTaskFinished = c; }
    public boolean stopped() { return stopped; }

    // ---------- scheduling ----------

    public void schedule(long delayMs, String label, Runnable action) {
        scheduleAt(now + delayMs, label, action);
    }

    public void scheduleAt(long at, String label, Runnable action) {
        if (at < now) throw new IllegalStateException("cannot schedule into the past: " + at + " < " + now);
        queue.add(new Event(at, seq++, label, action));
    }

    public void log(TraceEvent e) { trace.add(e); }

    public void log(String kind, String vm, Object... kv) {
        trace.add(TraceEvent.of(now, kind, vm, kv));
    }

    public void stop() { stopped = true; }
    public void runUntil(long t) { this.runUntil = t; }

    // ---------- tasks ----------

    public Task newTask(String vm, String name, Runnable body) {
        Task t = new Task(this, vm, name, body);
        tasks.add(t);
        t.launch();
        return t;
    }

    /** Start a task now: it runs until it yields. */
    public void activate(Task t) { resume(t); }

    /** Hand control to a task and block until it yields back. */
    public void resume(Task t) {
        if (t.done) return;
        if (running != null) throw new IllegalStateException(
                "handoff invariant broken: " + running.name + " still running while resuming " + t.name);
        running = t;
        slices++;
        t.permit.release();
        kernelPermit.acquireUninterruptibly();
        running = null;
    }

    /** Called from a task to hand control back. */
    void yieldFromTask() { kernelPermit.release(); }

    void taskFailed(Task t, Throwable err) {
        if (failure == null) failure = err;
        log("error", t.vm, "task", t.name, "error", err.getClass().getSimpleName(),
                "message", String.valueOf(err.getMessage()));
    }

    void taskFinished(Task t) { onTaskFinished.accept(t); }

    public void cancel(Task t) {
        if (t.done) return;
        t.cancelled = true;
        // A cancelled task is unwound the next time it would have been resumed.
    }

    // ---------- the loop ----------

    public void run() {
        while (!queue.isEmpty() && !stopped) {
            Event e = queue.peek();
            if (e.time() > runUntil) break;
            queue.poll();
            if (e.time() < now) throw new IllegalStateException("time went backwards");
            now = e.time();
            e.action().run();
        }
        if (failure != null) throw new SimulationFailed(failure);
    }

    public static final class SimulationFailed extends RuntimeException {
        public SimulationFailed(Throwable cause) { super(cause.toString(), cause); }
    }
}
