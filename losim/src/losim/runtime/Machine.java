package losim.runtime;

import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import losim.api.Bound;
import losim.api.Cost;
import losim.res.InstanceSpec;
import losim.res.Meter;
import losim.res.Retained;
import losim.trace.Telemetry;
import losim.trace.Values;

/**
 * One machine: its own gRPC server, its own threads, its own budget.
 *
 * <p>The thread pool is the whole model. It is sized to the instance's vCPU
 * count, so four concurrent handlers on a two-vCPU machine really do queue and a
 * handler really can race with itself. It is also the unit of measurement,
 * because every thread in it belongs to exactly one machine — which is what makes
 * {@code getThreadAllocatedBytes} an exact attribution rather than an estimate.
 *
 * <p>Two consequences follow and neither is negotiable. {@code directExecutor()}
 * must never be used, or the caller's thread runs the handler and the vCPU model
 * evaporates. And the threads must be platform threads, because
 * {@code getThreadAllocatedBytes} returns −1 for a virtual one.
 */
public final class Machine implements Bound, Telemetry.Sampled {

    /** Heap walks cost ~0.06 µs an object, so a busy machine is not walked every tick. */
    private static final int WALK_EVERY_TICKS = 8;

    private final Fleet fleet;
    final String name, zone;
    final InstanceSpec spec;
    final int vcpu;
    final double memoryCapMb, diskCapMb;
    /** How much slower than the reference machine this one is (D3). */
    final double machineFactor;

    final ThreadPoolExecutor pool;
    private final long[] threadIds;
    private final long allocAtBoot;
    private Server server;

    volatile boolean alive = true;
    volatile String deadReason;

    final AtomicInteger inflight = new AtomicInteger();   // handlers using a core
    final AtomicInteger queued   = new AtomicInteger();   // handlers waiting for one
    final AtomicLong bytesIn     = new AtomicLong();
    final AtomicLong bytesOut    = new AtomicLong();
    final AtomicLong handled     = new AtomicLong();
    final AtomicLong diskBytes   = new AtomicLong();
    final AtomicLong retainedBytes = new AtomicLong();

    // losim's own footprint on these threads, metered so it can be taken back off.
    final AtomicLong losimBytes   = new AtomicLong();
    final AtomicLong losimNanos   = new AtomicLong();
    final AtomicLong losimRegions = new AtomicLong();

    private final List<Object> roots = new CopyOnWriteArrayList<>();
    private final Map<String, Cost> costs = new ConcurrentHashMap<>();
    private final List<String> servicesOffered = new CopyOnWriteArrayList<>();
    private int sinceWalk = WALK_EVERY_TICKS;            // walk on the very first tick
    private volatile boolean oomReported;

    Machine(Fleet fleet, String name, InstanceSpec spec, String zone,
            double memoryCapMb, double diskCapMb) {
        this.fleet = fleet;
        this.name = name;
        this.spec = spec;
        this.zone = zone;
        this.vcpu = (int) Math.max(1, Math.round(spec.vcpu()));
        this.memoryCapMb = memoryCapMb;
        this.diskCapMb = diskCapMb;
        this.machineFactor = spec.cpuFactor();

        String threadName = "losim-" + name + "-w";
        this.pool = new ThreadPoolExecutor(vcpu, vcpu, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> { Thread t = new Thread(r, threadName); t.setDaemon(true); return t; });
        pool.prestartAllCoreThreads();

        var ids = new ArrayList<Long>();
        for (Thread t : Thread.getAllStackTraces().keySet())
            if (t.getName().equals(threadName)) ids.add(t.threadId());
        this.threadIds = ids.stream().mapToLong(Long::longValue).toArray();
        this.allocAtBoot = Meter.allocatedBy(threadIds);
    }

    Fleet fleet() { return fleet; }
    Telemetry tel() { return fleet.tel; }
    public String name() { return name; }
    public int vcpu()    { return vcpu; }
    public boolean alive() { return alive; }
    public double memoryCapMb() { return memoryCapMb; }

    // ------------------------------------------------------------------ serving

    /** Registers services, wraps them in losim's server interceptor, and starts listening. */
    public Machine serving(BindableService... services) {
        // The machine's own pool runs the handlers, wrapped so that time spent
        // waiting for a core is separable from time spent using one. Without the
        // wrapper a queued call is indistinguishable from a slow one.
        Executor queueing = r -> {
            queued.incrementAndGet();
            double enq = tel().now();
            pool.execute(() -> {
                queued.decrementAndGet();
                double wait = tel().now() - enq;
                if (wait > 0.001) tel().event(name, "queue_wait", "ms", round(wait));
                r.run();
            });
        };
        var b = InProcessServerBuilder.forName(name).executor(queueing);
        for (BindableService s : services) {
            roots.add(s);                                // a machine's data hangs off its services
            costs.putAll(Costs.of(s));
            var def = s.bindService();
            String svc = def.getServiceDescriptor().getName();
            servicesOffered.add(svc.substring(svc.lastIndexOf('.') + 1));
            fleet.offers(svc, name);
            b.addService(ServerInterceptors.intercept(def, new ServerSide(this)));
        }
        try { server = b.build().start(); }
        catch (Exception e) { throw new IllegalStateException("could not start machine " + name, e); }
        tel().event(name, "boot", "instance", spec.name(), "zone", zone, "vcpu", vcpu,
                    "memoryMb", memoryCapMb, "diskMb", diskCapMb);
        return this;
    }

    Cost costOf(String fullMethodName) { return costs.get(fullMethodName); }

    /**
     * Work this machine does that no RPC carried.
     *
     * <p>Without it a coordinator merging locally is indistinguishable from one
     * doing nothing: no span covers it, no series shows the machine busy, and the
     * most expensive stretch of a run reads as idle (D8 rule 5).
     */
    public <T> T compute(String label, java.util.function.Supplier<T> body) {
        Telemetry.Span span = tel().open(name, "compute", label);
        inflight.incrementAndGet();
        try {
            T out = body.get();
            tel().close(span, "OK", "result", tel().payloads() ? Values.render(out) : null);
            return out;
        } catch (RuntimeException e) {
            tel().close(span, "FAILED", "error", String.valueOf(e.getMessage()));
            throw e;
        } finally {
            inflight.decrementAndGet();
        }
    }

    /** A channel to a peer, with losim's client interceptor already on it. */
    public ManagedChannel channelTo(String peer) {
        return InProcessChannelBuilder.forName(peer).usePlaintext()
                .intercept(new ClientSide(this, peer)).build();
    }

    /**
     * Runs work on this machine's own threads, with the machine ambient.
     *
     * <p>This is how a coordinator drives its own calls. It matters for more than
     * tidiness: work started on a thread the machine did not create carries no
     * machine identity, so its memory and CPU are attributed to nobody, and losim
     * can only flag it rather than count it (D11).
     */
    public java.util.concurrent.Future<?> submit(Runnable r) {
        return pool.submit(io.grpc.Context.current()
                .withValue(losim.api.Ambient.MACHINE, this).wrap(r));
    }

    public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> c) {
        return pool.submit(io.grpc.Context.current()
                .withValue(losim.api.Ambient.MACHINE, this).wrap(c));
    }

    public void kill(String reason) {
        alive = false;
        deadReason = reason;
        tel().event(name, "kill", "reason", reason);
    }

    // ------------------------------------------------------------------- ledger

    /**
     * What the <i>program</i> allocated.
     *
     * <p>losim's own bookkeeping runs on these same threads, so it is metered
     * separately and taken back off. Reporting the sum would charge a machine for
     * being watched, and — worse — charge it more the more heavily its program is
     * instrumented (D13).
     */
    public long allocatedBytes() {
        return Math.max(0, rawAllocatedBytes() - losimBytes.get());
    }

    /** Before the subtraction. Only for measuring the observer effect itself. */
    public long rawAllocatedBytes() {
        return Meter.allocatedBy(threadIds) - allocAtBoot;
    }

    public long losimBytes()   { return losimBytes.get(); }
    public long losimNanos()   { return losimNanos.get(); }
    public long losimRegions() { return losimRegions.get(); }
    public long handledCalls() { return handled.get(); }
    public long bytesOut()     { return bytesOut.get(); }
    public long bytesIn()      { return bytesIn.get(); }

    /** Charges a metered region of losim's own work to this machine, and to the span it sat in. */
    @Override public void charge(long bytes, long nanos) {
        chargeTo(Telemetry.SPAN.get(), bytes, nanos);
    }

    void chargeTo(Telemetry.Span span, long bytes, long nanos) {
        long owed = nanos + Meter.UNSEEN_NANOS_PER_REGION;
        losimBytes.addAndGet(bytes);
        losimNanos.addAndGet(owed);
        losimRegions.incrementAndGet();
        if (span != null) span.losimNanos.addAndGet(owed);
    }

    // ---------------------------------------------------------------- the facade

    @Override public void event(String kind, Object... kv) { tel().event(name, kind, kv); }

    @Override public void records(long n) {
        Telemetry.Span s = Telemetry.SPAN.get();
        if (s != null) s.records.set(n);
    }

    @Override public List<String> peers() {
        return fleet.names().stream().filter(n -> !n.equals(name)).toList();
    }

    @Override public List<String> peersServing(String service) { return fleet.serving(service); }

    @Override public double clockMs() { return tel().now(); }

    // -------------------------------------------------------------------- disk

    public void wroteDisk(long bytes) { diskBytes.addAndGet(bytes); }
    public long diskBytes() { return diskBytes.get(); }

    // -------------------------------------------------------------------- heap

    /** Anything this machine holds that is not reachable from one of its services. */
    public void alsoHolds(Object o) { roots.add(o); }

    /** Walks now, regardless of cadence. The probe grid needs a figure at a known instant. */
    public Retained.Result measureRetained() {
        var r = Retained.of(roots, this::notMine);
        retainedBytes.set(r.bytes());
        return r;
    }

    public long retainedBytes() { return retainedBytes.get(); }

    private void walkHeapIfDue() {
        if (++sinceWalk < WALK_EVERY_TICKS) return;
        sinceWalk = 0;
        var r = Retained.of(roots, this::notMine);
        retainedBytes.set(r.bytes());
        double usedMb = r.bytes() / 1048576.0;
        if (usedMb > memoryCapMb && !oomReported) {
            oomReported = true;
            tel().event(name, "oom", "resource", "memory", "capMb", memoryCapMb,
                        "demandMb", round(usedMb), "objects", r.objects(),
                        "cause", "retained heap exceeded the machine");
            kill("out of memory");
        }
    }

    /**
     * The edge of this machine.
     *
     * <p>Its own plumbing is not its data; another machine's heap certainly is
     * not; gRPC's transport is shared by everyone. But the protobuf messages it is
     * holding <i>are</i> exactly its data. losim's own structures sit outside every
     * machine's boundary, which is asserted rather than assumed (D13 rule 7).
     */
    private boolean notMine(Object o) {
        if (o instanceof Machine || o instanceof Fleet || o instanceof Telemetry
            || o instanceof Telemetry.Span || o instanceof losim.time.Clock || o instanceof Net
            || o instanceof Server || o instanceof Channel
            || o instanceof Executor || o instanceof Thread) return true;
        String n = o.getClass().getName();
        return n.startsWith("io.grpc.")
            || n.startsWith("losim.trace.")
            || n.startsWith("com.google.protobuf.Descriptors");
    }

    // ------------------------------------------------------------------ sampled

    @Override public String vmName() { return name; }

    @Override public void sample(Map<String, Double> into) {
        walkHeapIfDue();
        double retainMb = retainedBytes.get() / 1048576.0;
        double diskMb   = diskBytes.get() / 1048576.0;
        into.put("allocMb",    allocatedBytes() / 1048576.0);
        into.put("retainMb",   retainMb);
        into.put("memCapMb",   memoryCapMb);
        into.put("memPct",     retainMb / memoryCapMb * 100);
        into.put("diskMb",     diskMb);
        into.put("diskPct",    diskCapMb > 0 ? diskMb / diskCapMb * 100 : 0);
        into.put("inflight",   (double) inflight.get());
        into.put("queued",     (double) queued.get());
        into.put("vcpu",       (double) vcpu);
        into.put("busyPct",    Math.min(100.0, inflight.get() * 100.0 / vcpu));
        into.put("alive",      alive ? 1.0 : 0.0);
        into.put("bytesInMb",  bytesIn.get() / 1048576.0);
        into.put("bytesOutMb", bytesOut.get() / 1048576.0);
    }

    void shutdown() {
        if (server != null) server.shutdownNow();
        pool.shutdownNow();
    }

    static double round(double x) { return Math.round(x * 1000) / 1000.0; }
}
