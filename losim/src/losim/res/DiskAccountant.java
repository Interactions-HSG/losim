package losim.res;

import losim.api.Faults;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A virtual disk: quota, fsync latency, and writes that are lost on crash if
 * they were never flushed — which is the whole durability-versus-latency lesson.
 */
public final class DiskAccountant {

    private final String vm;
    private final long quotaBytes;
    private final Map<String, byte[]> durable = new LinkedHashMap<>();
    private final Map<String, byte[]> pending = new LinkedHashMap<>();
    private final Map<String, Long> durableSizes = new LinkedHashMap<>();
    private final Map<String, Long> pendingSizes = new LinkedHashMap<>();
    private long used;

    public DiskAccountant(String vm, long quotaBytes) {
        this.vm = vm; this.quotaBytes = quotaBytes;
    }

    public long used() { return used; }
    public long quota() { return quotaBytes; }
    public int pendingCount() { return pending.size(); }

    public void write(String key, byte[] value) {
        reserve(key, value.length);
        pending.put(key, value);
    }

    /**
     * Reserve space for a described volume without holding it.
     * A terabyte spill costs a terabyte of quota and no heap at all.
     */
    public void reserve(String key, long bytes) {
        long delta = bytes - size(key);
        if (used + delta > quotaBytes)
            throw new Faults.NoSpace(vm + " disk full: needs " + (used + delta)
                    + " B but the disk is " + quotaBytes + " B");
        used += delta;
        pendingSizes.put(key, bytes);
    }

    public byte[] read(String key) {
        byte[] p = pending.get(key);
        return p != null ? p : durable.get(key);
    }

    /** Make every pending write survivable. The caller pays the latency. */
    public void fsync() {
        durable.putAll(pending);
        durableSizes.putAll(pendingSizes);
        pending.clear();
        pendingSizes.clear();
    }

    /** A crash: unflushed writes never happened. */
    public void crash() {
        for (Map.Entry<String, Long> e : pendingSizes.entrySet()) {
            Long old = durableSizes.get(e.getKey());
            used -= e.getValue();
            used += old == null ? 0 : old;
        }
        pending.clear();
        pendingSizes.clear();
    }

    private long size(String key) {
        Long p = pendingSizes.get(key);
        if (p != null) return p;
        Long d = durableSizes.get(key);
        return d == null ? 0 : d;
    }
}
