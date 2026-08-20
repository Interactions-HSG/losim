package losim.runtime;

import losim.api.*;
import losim.api.Ctx;

import losim.kernel.Task;
import losim.res.DiskAccountant;
import losim.res.InstanceSpec;
import losim.res.MemoryAccountant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A machine: the resource container and the unit of failure. */
public final class Vm {

    public enum State { ALIVE, FROZEN, DEAD }

    public final String name;
    public final InstanceSpec spec;
    public final String zone;
    public final String market;               // "on-demand" | "spot"

    public final MemoryAccountant mem;
    public final DiskAccountant disk;

    public final List<Program> programs = new ArrayList<>();
    public final Map<Program, Ctx> contexts = new LinkedHashMap<>();
    public final List<Task> tasks = new ArrayList<>();
    public final List<Task> deferred = new ArrayList<>();

    public State state = State.ALIVE;
    public double cpuMultiplier = 1.0;        // a degrade fault
    public long creditMsRemaining;            // burstable instances
    public long busyUntil = 0;                // for the gantt view
    public long busyMs = 0;
    public long bytesIn = 0, bytesOut = 0, crossZoneBytes = 0;
    public long bootedAt = 0;
    public long diedAt = -1;

    public Vm(String name, InstanceSpec spec, String zone, String market) {
        this.name = name; this.spec = spec; this.zone = zone; this.market = market;
        this.mem = new MemoryAccountant(name, spec.memoryMb() * 1024L * 1024L);
        this.disk = new DiskAccountant(name, spec.storageGb() * 1024L * 1024L * 1024L);
        this.creditMsRemaining = spec.creditSeconds() * 1000L;
    }

    public boolean alive() { return state == State.ALIVE; }
    /** A frozen VM is still there — it simply is not running. Only death drops traffic. */
    public boolean isDead() { return state == State.DEAD; }

    /** Declared cost scaled by this machine's speed, burst credits and any degrade. */
    public long effectiveCostMs(long declaredMs) {
        if (declaredMs <= 0) return 0;
        double factor;
        if (spec.burstable() && creditMsRemaining <= 0) factor = spec.throttledFactor();
        else factor = spec.cpuFactor();
        factor *= cpuMultiplier;
        long cost = Math.max(1, Math.round(declaredMs * factor));
        if (spec.burstable() && creditMsRemaining > 0) creditMsRemaining -= Math.min(creditMsRemaining, cost);
        return cost;
    }

    public boolean hosts(Class<?> serviceInterface) {
        for (Program p : programs) if (serviceInterface.isInstance(p)) return true;
        return false;
    }

    public Program provider(Class<?> serviceInterface) {
        for (Program p : programs) if (serviceInterface.isInstance(p)) return p;
        return null;
    }
}
