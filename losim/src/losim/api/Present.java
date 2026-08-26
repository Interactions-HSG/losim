package losim.api;

import java.util.List;
import losim.res.Meter;

/**
 * losim inside a run.
 *
 * Every method here brackets its own body. The interceptors wrap a call and can
 * be metered from outside it; these run <i>inside</i> the handler, in the middle
 * of the student's own code, on the machine's own thread, between the two marks
 * that measure that handler. So each one measures itself and hands the bill to
 * losim (D13).
 *
 * <p>The boundary is exact and worth stating, because losim cannot cross it: in
 * {@code reveal("keys", map.size())} the {@code size()} is the program's work and
 * is charged to the program, correctly. Everything after the argument has been
 * evaluated is losim's.
 */
final class Present implements LosimCtx {

    @Override public boolean isRunning() { return true; }

    // ---------------------------------------------------------------- recording

    // Each overload takes a primitive, so the boxing happens in record() — inside
    // the bracket — rather than at the call site, where it would be charged to the
    // program (D13 rule 5).
    @Override public void reveal(String key, int value) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        record(key, value, a0, t0);
    }
    @Override public void reveal(String key, long value) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        record(key, value, a0, t0);
    }
    @Override public void reveal(String key, double value) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        record(key, value, a0, t0);
    }
    @Override public void reveal(String key, boolean value) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        record(key, value, a0, t0);
    }
    @Override public void reveal(String key, String value) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        record(key, value, a0, t0);
    }
    @Override public void reveal(String key, Object value) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        record(key, value, a0, t0);
    }

    private void record(String key, Object boxed, long a0, long t0) {
        Bound b = Ambient.MACHINE.get();
        if (b == null) return;                       // the machine went away mid-call
        b.event("state", "key", key, "value", boxed);
        b.charge(Meter.allocNow() - a0, System.nanoTime() - t0);
    }

    @Override public void log(String message) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        Bound b = Ambient.MACHINE.get();
        if (b == null) return;
        b.event("log", "message", message);
        b.charge(Meter.allocNow() - a0, System.nanoTime() - t0);
    }

    @Override public void records(long n) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        Bound b = Ambient.MACHINE.get();
        if (b == null) return;
        b.records(n);
        b.charge(Meter.allocNow() - a0, System.nanoTime() - t0);
    }

    @Override public void wroteDisk(long bytes) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        Bound b = Ambient.MACHINE.get();
        if (b == null) return;
        // In a finally, because the accounting is losim's cost either way — and the
        // refusal has to reach the handler, since a write that cannot happen must
        // not appear to have happened.
        try { b.wroteDisk(bytes); }
        finally { b.charge(Meter.allocNow() - a0, System.nanoTime() - t0); }
    }

    @Override public void sleep(double refMs) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        Bound b = Ambient.MACHINE.get();
        if (b == null) return;
        b.event("sleep", "refMs", refMs);
        b.charge(Meter.allocNow() - a0, System.nanoTime() - t0);

        // Outside the bracket, and that is the whole point of writing it this way:
        // the wait is the program's own declared duration. Charging it to losim
        // would subtract it straight back out of the span it exists to lengthen.
        b.sleep(refMs);
    }

    // -------------------------------------------------------------------- state

    // Reads, not records: they allocate nothing worth charging and are cheap
    // enough that bracketing them would cost more than it recovered.
    @Override public String machine()                    { return bound().name(); }
    @Override public List<String> peers()                { return bound().peers(); }
    @Override public List<String> peersServing(String s) { return bound().peersServing(s); }
    @Override public double clockMs()                    { return bound().clockMs(); }

    private static Bound bound() {
        Bound b = Ambient.MACHINE.get();
        if (b == null) throw new IllegalStateException(
                "this thread has no machine: work started on a thread the machine did not create "
              + "loses its identity, and its memory and CPU are attributed to nobody. "
              + "Wrap the machine's executor with Context.currentContextExecutor.");
        return b;
    }
}
