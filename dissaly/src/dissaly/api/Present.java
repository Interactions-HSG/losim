package dissaly.api;

import io.grpc.Channel;
import java.util.List;
import dissaly.res.Meter;

/**
 * The API context used while a simulation is running.
 *
 * Each method measures its own allocation and duration. The call interceptors
 * measure the handler from outside, while these methods run inside the handler's
 * span and charge their work to dissaly (D13).
 *
 * <p>The boundary is visible in {@code reveal("keys", map.size())}: evaluating
 * {@code size()} is program work. Boxing and recording the value are dissaly work.
 */
final class Present implements DissalyCtx {

    @Override public boolean isRunning() { return true; }

    // ---------------------------------------------------------------- recording

    // Primitive overloads keep boxing inside record(), where it is charged to dissaly.
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

    @Override public void units(long n) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        Bound b = Ambient.MACHINE.get();
        if (b == null) return;
        b.units(n);
        b.charge(Meter.allocNow() - a0, System.nanoTime() - t0);
    }

    @Override public void wroteDisk(long bytes) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        Bound b = Ambient.MACHINE.get();
        if (b == null) return;
        // Charge the accounting even when the disk cap rejects the write.
        try { b.wroteDisk(bytes); }
        finally { b.charge(Meter.allocNow() - a0, System.nanoTime() - t0); }
    }

    @Override public void alsoHolds(Object held) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        Bound b = Ambient.MACHINE.get();
        if (b == null) return;
        // Registering a root costs a list append and is charged like any other
        // call the program makes. What it does *not* do is measure anything: the
        // walk happens on the sampler's cadence, so the cost of holding this shows
        // up where the holding does rather than where it was declared.
        try { b.alsoHolds(held); }
        finally { b.charge(Meter.allocNow() - a0, System.nanoTime() - t0); }
    }

    @Override public void sleep(double refMs) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        Bound b = Ambient.MACHINE.get();
        if (b == null) return;
        b.event("sleep", "refMs", refMs);
        b.charge(Meter.allocNow() - a0, System.nanoTime() - t0);

        // The wait is program time, so keep it outside the dissaly accounting bracket.
        b.sleep(refMs);
    }

    // -------------------------------------------------------------------- state

    // State reads allocate too little to justify a measurement bracket.
    @Override public String node()                       { return bound().name(); }
    @Override public long seed()                         { return bound().seed(); }
    @Override public java.util.concurrent.ConcurrentMap<String, Object> local() {
        return bound().local();
    }
    @Override public Spec here()                         { return bound().here(); }
    @Override public List<String> peers()                { return bound().peers(); }
    @Override public List<String> peersServing(String s) { return bound().peersServing(s); }
    @Override public double clockMs()                    { return bound().clockMs(); }

    /**
     * The first call to a peer builds a channel and its interceptors. Charge that
     * setup to dissaly rather than to the program that requested the channel.
     */
    @Override public Channel channelTo(String machine) {
        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        Bound b = bound();
        try { return b.dial(machine); }
        finally { b.charge(Meter.allocNow() - a0, System.nanoTime() - t0); }
    }

    private static Bound bound() {
        Bound b = Ambient.MACHINE.get();
        if (b == null) throw new IllegalStateException(
                "this thread has no machine: work started on a thread the machine did not create "
              + "loses its identity, and its memory and CPU are attributed to nobody. "
              + "Wrap the machine's executor with Context.currentContextExecutor.");
        return b;
    }
}
