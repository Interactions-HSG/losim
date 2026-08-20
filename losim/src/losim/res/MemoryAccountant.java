package losim.res;

import losim.api.Faults;

/**
 * Per-VM memory accounting.
 *
 * Deliberately DETERMINISTIC: it counts the logical bytes a VM retains, not the
 * JVM's real allocation. Sampled allocation varies with JIT and GC, and since
 * crossing the cap raises OutOfMemory — which changes control flow — a measured
 * number would make the whole simulation irreproducible. Logical bytes are the
 * same on every machine and every run.
 */
public final class MemoryAccountant {

    private final String vm;
    private final long capBytes;
    private long used;
    private long peak;

    public MemoryAccountant(String vm, long capBytes) {
        this.vm = vm; this.capBytes = capBytes;
    }

    public long used() { return used; }
    public long peak() { return peak; }
    public long cap() { return capBytes; }

    public void add(long bytes) {
        used += bytes;
        if (used > peak) peak = used;
        if (used > capBytes) {
            long over = used;
            used = capBytes;                       // the VM is about to die anyway
            throw new Faults.OutOfMemory(vm + " needs " + over + " B but has " + capBytes + " B");
        }
    }

    public void release(long bytes) { used = Math.max(0, used - bytes); }

    public void reset() { used = 0; }
}
