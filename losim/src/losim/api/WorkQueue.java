package losim.api;


import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A deterministic work queue with explicit completion.
 *
 * Iterating blocks while work is outstanding, so several worker drivers can pull
 * from it concurrently. {@link #requeue} is what a failure strategy calls; the
 * duplicate execution that follows is the reason {@link #done} must be idempotent.
 */
public final class WorkQueue<T> implements Iterable<T> {

    private final ArrayDeque<T> pending = new ArrayDeque<>();
    private final Set<T> inFlight = new LinkedHashSet<>();
    private final Set<T> completed = new LinkedHashSet<>();
    private final Ctx ctx;

    public WorkQueue(Ctx ctx, List<T> items) {
        this.ctx = ctx;
        pending.addAll(items);
    }

    public synchronized boolean isComplete() {
        return pending.isEmpty() && inFlight.isEmpty();
    }

    public boolean done(T item) {
        boolean first = !completed.contains(item);
        completed.add(item);
        inFlight.remove(item);
        pending.remove(item);
        ctx.runtime().wakeAll(this);
        return first;                                  // false => this was duplicate work
    }

    public void requeue(T item) {
        inFlight.remove(item);
        if (!completed.contains(item) && !pending.contains(item)) pending.addFirst(item);
        ctx.runtime().wakeAll(this);
    }

    public boolean isDone(T item) { return completed.contains(item); }
    public int completedCount() { return completed.size(); }
    public int remaining() { return pending.size() + inFlight.size(); }

    @Override public Iterator<T> iterator() {
        return new Iterator<>() {
            T next;
            @Override public boolean hasNext() {
                while (true) {
                    if (!pending.isEmpty()) {
                        next = pending.pollFirst();
                        inFlight.add(next);
                        return true;
                    }
                    if (inFlight.isEmpty()) return false;      // nothing left anywhere
                    ctx.runtime().await(WorkQueue.this);       // work is out; wait for its fate
                }
            }
            @Override public T next() { return next; }
        };
    }
}
