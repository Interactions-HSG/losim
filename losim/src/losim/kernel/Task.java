package losim.kernel;

import java.util.concurrent.Semaphore;

/**
 * A kernel-owned virtual thread. Student code never creates one.
 *
 * The permit is half of the strict handoff: the kernel releases it to run this
 * task, the task releases the kernel's permit to give control back. At most one
 * of the two is ever runnable, which is what makes the schedule deterministic.
 */
public final class Task {
    public final String name;
    public final String vm;
    final Kernel kernel;
    final Semaphore permit = new Semaphore(0);
    private final Runnable body;

    volatile boolean done;
    volatile boolean cancelled;
    Thread thread;

    Task(Kernel kernel, String vm, String name, Runnable body) {
        this.kernel = kernel; this.vm = vm; this.name = name; this.body = body;
    }

    void launch() {
        thread = Thread.ofVirtual().name("task-" + vm + "-" + name).unstarted(() -> {
            permit.acquireUninterruptibly();               // wait for first activation
            try {
                if (!cancelled) body.run();
            } catch (Killed k) {
                // a kill unwinds the task silently; the VM is gone
            } catch (Throwable t) {
                kernel.taskFailed(this, t);
            } finally {
                done = true;
                kernel.taskFinished(this);
                kernel.yieldFromTask();                    // final yield to the kernel
            }
        });
        thread.start();
    }

    /** Give control back to the kernel and park until resumed. */
    public void yieldToKernel() {
        kernel.yieldFromTask();
        permit.acquireUninterruptibly();
        if (cancelled) throw new Killed();
    }

    public boolean isDone() { return done; }

    /** Thrown inside a task whose VM was killed, to unwind it. */
    public static final class Killed extends RuntimeException {
        public Killed() { super(null, null, false, false); }
    }
}
