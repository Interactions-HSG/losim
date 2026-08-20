package losim.api;

import losim.kernel.Task;
import losim.runtime.Runtime;
import losim.runtime.Vm;
import losim.runtime.Values;
import losim.api.Data;

import losim.kernel.Task;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Everything a program can do. Nothing else is reachable: no clock, no threads,
 * no sockets, no files. That is what makes a run reproducible.
 */
public final class Ctx {

    private final Runtime rt;
    private final Vm vm;
    private final Program program;
    private final Random random;
    private final List<Task> children = new ArrayList<>();

    /** Internal: only the runtime mints a Ctx. */
    public Ctx(Runtime rt, Vm vm, Program program, long seed) {
        this.rt = rt; this.vm = vm; this.program = program;
        this.random = new Random(seed);
    }

    /** Internal plumbing; not part of the lesson. */
    public Runtime runtime() { return rt; }

    // ---- identity ----
    public String name() { return vm.name; }
    public VmRef self() { return new VmRef(vm.name); }
    public String zone() { return vm.zone; }
    public Object input() { return rt.input(); }

    // ---- time and chance ----
    public long clock() { return rt.kernel().now(); }
    public Random random() { return random; }
    public void sleep(Duration d) { rt.sleepMs(d.toMillis()); }
    public void sleep(long ms) { rt.sleepMs(ms); }
    /** Burn declared compute time. Equivalent to a @Cost on a handler. */
    public void compute(long declaredMs) { rt.sleepMs(vm.effectiveCostMs(declaredMs)); }

    // ---- logging ----
    public void log(String message) { rt.kernel().log("log", vm.name, "message", message); }
    public void reveal(String key, Object value) {
        rt.kernel().log("state", vm.name, "key", key, "value", Values.render(value));
    }

    // ---- fleet ----
    public List<VmRef> fleet() {
        List<VmRef> out = new ArrayList<>();
        for (Vm v : rt.vms()) out.add(new VmRef(v.name));
        return out;
    }
    public int fleetSize() { return rt.vms().size(); }
    public boolean isOrigin() { return rt.vms().iterator().next().name.equals(vm.name); }

    /** The next VM in declaration order, wrapping. The ring topology. */
    public VmRef next() {
        List<Vm> all = new ArrayList<>(rt.vms());
        for (int i = 0; i < all.size(); i++)
            if (all.get(i).name.equals(vm.name)) return new VmRef(all.get((i + 1) % all.size()).name);
        throw new IllegalStateException("vm not in fleet");
    }

    // ---- Style A messaging ----
    public void send(VmRef to, Object message) { rt.send(vm, to.name(), message); }

    // ---- Style B RPC ----
    /** Every VM implementing this service. The CONFIGURED fleet, not the live one. */
    public <P extends Peer> List<P> peers(Class<P> peerType) { return rt.peers(vm, peerType); }

    /** A provider of this service on that same machine — loopback, free, instant. */
    public <P extends Peer> Optional<P> local(Peer on, Class<P> peerType) {
        return rt.localPeer(vm, on.name(), peerType);
    }
    public boolean hasLocal(Peer on, Class<? extends Peer> peerType) {
        return rt.localPeer(vm, on.name(), peerType).isPresent();
    }

    /** Run a call with a deadline. Too short duplicates work; too long stalls. */
    public <T> T within(Duration d, Supplier<T> body) { return rt.within(d.toMillis(), body); }

    // ---- concurrency, kernel-owned ----
    public void spawn(Runnable body) {
        children.add(rt.spawn(vm, "child" + children.size(), body));
    }
    public void awaitAll() { rt.awaitTasks(children); }

    public <T> WorkQueue<T> workQueue(List<T> items) { return new WorkQueue<>(this, items); }

    // ---- simulated workloads: act as if, without actually doing it ----

    /**
     * Process a described dataset: bills virtual CPU time for the records,
     * scaled by this machine's speed. Nothing is materialised.
     */
    public void process(Data data, double nsPerRecord) {
        long ms = Math.round(data.records() * nsPerRecord / 1_000_000.0);
        rt.kernel().log("work", vm.name, "data", data.name(), "records", data.records(),
                "gb", Math.round(data.gigabytes() * 100) / 100.0, "declaredMs", ms);
        compute(Math.max(1, ms));
    }

    /**
     * Hold a described dataset in memory. Raises OutOfMemory when it does not
     * fit the machine you provisioned — which is the point of provisioning.
     */
    public void hold(Data data) { vm.mem.add(data.bytes()); }
    public void release(Data data) { vm.mem.release(data.bytes()); }

    /** Charge bytes to memory directly. */
    public void allocate(long bytes) { vm.mem.add(bytes); }
    public void free(long bytes) { vm.mem.release(bytes); }

    /** Spill to local disk. Raises NoSpace when the volume exceeds the disk. */
    public void spill(Data data) { vm.disk.reserve("spill:" + data.name(), data.bytes()); }

    public long memoryFree() { return vm.mem.cap() - vm.mem.used(); }
    public long diskFree() { return vm.disk.quota() - vm.disk.used(); }

    // ---- resources ----
    /** Charge bytes to this VM's memory for good. Crossing the cap raises OutOfMemory. */
    public void retain(Object value) { vm.mem.add(rt.codec().serializedSize(value)); }
    public void retainBytes(long bytes) { vm.mem.add(bytes); }
    public long memoryUsed() { return vm.mem.used(); }

    public void write(String key, byte[] value) { vm.disk.write(key, value); }
    public byte[] read(String key) { return vm.disk.read(key); }
    /** Make writes survive a crash. You pay for the durability. */
    public void fsync() { rt.sleepMs(vm.effectiveCostMs(5)); vm.disk.fsync(); }

    // ---- result ----
    public void done(Object value) { rt.done(value); }
}
