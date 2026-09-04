package losim.runtime;

import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import losim.api.Bound;
import losim.api.Takes;
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

    /**
     * The region this machine's zone is in, worked out once.
     *
     * <p>Once, and here, because a machine does not move and the alternative is
     * parsing a zone name on every call — on the caller's own thread, where it
     * would be charged to the student's program and land in the fixed term the
     * scale engine fits laws on. losim's own work is metered and taken back off
     * (D11); work that need not happen at all is better than either.
     */
    final String region;
    final InstanceSpec spec;
    final int vcpu;
    final double memoryCapMb, diskCapMb;
    /** How much slower than the reference machine this one is (D3). */
    final double machineFactor;
    private volatile double degraded = 1.0;
    private volatile long frozenUntilNs;
    private final List<java.util.function.Supplier<? extends BindableService>> factories =
            new CopyOnWriteArrayList<>();
    private volatile boolean rebuildable;
    private final List<io.grpc.MethodDescriptor<?, ?>> served = new CopyOnWriteArrayList<>();

    final ThreadPoolExecutor pool;
    private final long[] threadIds;
    private final long allocAtBoot;
    private Server server;

    /**
     * What this machine is made of, as a program running on it may ask.
     *
     * <p>Built once here rather than per call: every field it holds is already
     * final, so there is nothing to measure and nothing to charge anyone for.
     */
    private final losim.api.Spec here;

    volatile boolean alive = true;

    /**
     * Set once this machine has been shut down, and never cleared.
     *
     * <p>A fault scheduled inside the run's horizon can still be in flight when the
     * run ends. The dispatcher is stopped before the fleet is torn down, which is
     * the tidy half, but stopping the thing that schedules faults does not stop a
     * fault already running — so a {@code restart_after} could land after every
     * machine had given its name back, and bind this one's name into a fleet that
     * no longer exists. Nobody would ever release it, and the <i>next</i> fleet in
     * the same JVM would find the name taken.
     *
     * <p>That is why it showed up in the probe grid and only under chaos: thirty
     * fleets back to back in one JVM, every one of them with a machine called
     * {@code m0}, and enough scheduled restarts for one to fall off the end.
     *
     * <p>The invariant this enforces is the half that holds whoever fires late: a
     * machine that has been shut down stays shut down.
     */
    private volatile boolean stopped;
    volatile String deadReason;

    final AtomicInteger inflight = new AtomicInteger();   // handlers using a core
    final AtomicInteger queued   = new AtomicInteger();   // handlers waiting for one
    final AtomicLong bytesIn     = new AtomicLong();
    final AtomicLong bytesOut    = new AtomicLong();
    final AtomicLong handled     = new AtomicLong();
    final AtomicLong diskBytes   = new AtomicLong();
    final AtomicLong retainedBytes = new AtomicLong();
    final AtomicLong peakRetainedBytes = new AtomicLong();

    /** Bytes that left this machine for another zone, which is the traffic anyone pays for. */
    final AtomicLong crossZoneBytes = new AtomicLong();

    /**
     * The same bytes again, split by the region they were sent to.
     *
     * <p>Because a byte to the zone next door and a byte to Sydney are not the
     * same price, and a single total cannot be billed at two rates. The split is
     * kept rather than derived at the end because only the call site knows both
     * ends of a call; by the time the run is over, the destination is gone.
     *
     * <p>Its values sum to {@link #crossZoneBytes} exactly — same bytes, counted
     * once each, under two headings.
     */
    final Map<String, AtomicLong> egressTo = new ConcurrentHashMap<>();

    // losim's own footprint on these threads, metered so it can be taken back off.
    final AtomicLong losimBytes   = new AtomicLong();
    final AtomicLong losimNanos   = new AtomicLong();
    final AtomicLong losimStops = new AtomicLong();

    private final List<Object> roots = new CopyOnWriteArrayList<>();
    private final Map<String, Takes> declared = new ConcurrentHashMap<>();
    private final Map<String, ManagedChannel> dialled = new ConcurrentHashMap<>();
    private final List<String> servicesOffered = new CopyOnWriteArrayList<>();
    private int sinceWalk = WALK_EVERY_TICKS;            // walk on the very first tick
    private volatile boolean oomReported;
    private final java.util.concurrent.atomic.AtomicBoolean diskFullReported =
            new java.util.concurrent.atomic.AtomicBoolean();

    Machine(Fleet fleet, String name, InstanceSpec spec, String zone,
            double memoryCapMb, double diskCapMb) {
        this.fleet = fleet;
        this.name = name;
        this.spec = spec;
        this.zone = zone;
        this.region = losim.res.Regions.regionOf(zone);
        this.vcpu = (int) Math.max(1, Math.round(spec.vcpu()));
        this.memoryCapMb = memoryCapMb;
        this.diskCapMb = diskCapMb;
        this.machineFactor = spec.cpuFactor();
        this.here = new losim.api.Spec(name, spec.name(), zone, vcpu, memoryCapMb, diskCapMb);

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
    @Override public losim.api.Spec here() { return here; }
    public int vcpu()    { return vcpu; }
    public String instance() { return spec.name(); }
    public String zone()     { return zone; }

    /** Which region its zone is in — what decides the price of talking to it. */
    public String region()   { return region; }
    public boolean alive() { return alive; }
    public double memoryCapMb() { return memoryCapMb; }

    // ------------------------------------------------------------------ serving

    /**
     * Registers services and starts listening.
     *
     * <p>The instances are kept as they are, so a machine that dies and comes back
     * comes back <i>remembering</i>. That is usually not what a restart means, and
     * the restart event says so. Use {@link #serves(java.util.function.Supplier)}
     * to have losim build fresh ones.
     */
    public Machine serving(BindableService... services) {
        for (BindableService svc : services) factories.add(() -> svc);
        return start();
    }

    /**
     * Registers a service losim can rebuild.
     *
     * <p>A restarted machine gets a new instance, so whatever the old one was
     * holding is gone — which is what a restart is, and what makes "who redoes the
     * work" a real question rather than a formality.
     */
    public Machine serves(java.util.function.Supplier<? extends BindableService> factory) {
        factories.add(factory);
        rebuildable = true;
        return start();
    }

    private Machine start() {
        if (stopped) return this;      // see `stopped`: a shut-down machine never rebinds
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
        releaseName();
        roots.clear();
        declared.clear();
        served.clear();
        var b = InProcessServerBuilder.forName(name).executor(queueing);
        for (var factory : factories) {
            BindableService s = factory.get();
            roots.add(s);                                // a machine's data hangs off its services
            declared.putAll(Durations.of(s));
            var def = s.bindService();
            for (var m : def.getMethods()) served.add(m.getMethodDescriptor());
            String svc = def.getServiceDescriptor().getName();
            String bare = svc.substring(svc.lastIndexOf('.') + 1);
            if (!servicesOffered.contains(bare)) servicesOffered.add(bare);
            fleet.offers(svc, name);
            b.addService(ServerInterceptors.intercept(def, new ServerSide(this)));
        }
        try { server = b.build().start(); }
        catch (Exception e) {
            // Named, because "could not start machine m0" is not a diagnosis and
            // the thing it usually turns out to be — a name still held by the
            // previous run's server — is unguessable without it.
            throw new IllegalStateException("could not start machine " + name + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        if (announced) announceBoot();
        return this;
    }

    /**
     * Says this machine exists.
     *
     * <p>Held back until the run's clock starts, so a fleet's booting does not
     * appear to have happened before the run it belongs to.
     */
    void announceBoot() {
        announced = true;
        tel().event(name, "boot", "instance", spec.name(), "zone", zone, "vcpu", vcpu,
                    "memoryMb", memoryCapMb, "diskMb", diskCapMb);
    }

    private volatile boolean announced;

    // -------------------------------------------------------------------- faults

    /**
     * Stops the machine dead for a while, without killing it.
     *
     * <p>A stop-the-world pause, a swapping host, a machine that is simply not
     * scheduled. The calls do not fail — they wait, on the machine's own threads,
     * which is precisely why a freeze is so much harder to diagnose than a crash
     * and worth being able to stage.
     */
    public void freeze(double refMs) {
        long until = System.nanoTime() + (long) (refMs / tel().kTime() * 1e6);
        frozenUntilNs = Math.max(frozenUntilNs, until);
        tel().event(name, "freeze", "forRefMs", round(refMs));
    }

    /** The pause is over, whether or not anyone was waiting on it. */
    public void thaw() {
        if (frozenUntilNs == 0) return;
        frozenUntilNs = 0;
        tel().event(name, "thaw");
    }

    /**
     * Held at the door, so a frozen machine occupies its cores rather than refusing.
     *
     * <p>Rechecked every couple of milliseconds rather than parked once for the
     * whole window, so a machine thawed early is not still asleep.
     */
    void awaitThaw() {
        while (true) {
            long until = frozenUntilNs;
            if (until == 0) return;
            long left = until - System.nanoTime();
            if (left <= 0) return;
            fleet.clock.parkRealNanos(Math.min(left, 2_000_000));
        }
    }

    public boolean frozen() { return frozenUntilNs > System.nanoTime(); }

    /**
     * Makes the machine slower than its instance type says.
     *
     * <p>A noisy neighbour, a machine sharing a host, a degraded disk. Every declared
     * cost is multiplied, so the machine is a straggler rather than a casualty —
     * which is the failure most designs handle worst.
     */
    public void degrade(double factor) {
        degraded = Math.max(1.0, factor);
        tel().event(name, "degrade", "factor", round(degraded));
    }

    /** How much slower this machine is than the reference, including any degradation. */
    double effectiveFactor() { return machineFactor * degraded; }

    /** Brings a dead machine back. What it remembers depends on how it was registered. */
    public void restart() {
        if (stopped) return;
        boolean fresh = rebuildable;
        alive = true;
        deadReason = null;
        degraded = 1.0;
        frozenUntilNs = 0;
        oomReported = false;
        diskFullReported.set(false);
        diskBytes.set(0);
        retainedBytes.set(0);
        if (fresh) start();
        tel().event(name, "restart", "state", fresh ? "lost" : "kept",
                    "note", fresh ? "fresh services: whatever it was holding is gone"
                                  : "the same service instances: this machine came back remembering, "
                                    + "which a real one would not");
    }

    Takes takenBy(String fullMethodName) { return declared.get(fullMethodName); }

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

    /**
     * A channel to a peer, with losim's client side already on it.
     *
     * <p>The retry interceptor sits outside the recording one, so every attempt is
     * a genuinely separate call rather than a repeat of the same accounting.
     */
    public ManagedChannel channelTo(String peer) {
        var b = InProcessChannelBuilder.forName(peer).usePlaintext();
        return fleet.retries().isEmpty()
                ? b.intercept(new ClientSide(this, peer)).build()
                : b.intercept(new Retrying(this, fleet.retries()), new ClientSide(this, peer)).build();
    }

    /** Every service this machine offers, by name — how peers find it, and what it is. */
    public List<String> servicesOffered() { return List.copyOf(servicesOffered); }

    /** Every method this machine serves. The retry gate is checked against these. */
    List<io.grpc.MethodDescriptor<?, ?>> methods() { return List.copyOf(served); }

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

    public long crossZoneBytes() { return crossZoneBytes.get(); }

    /** Bytes this machine sent out of its zone, by destination region. */
    public Map<String, Long> egressByRegion() {
        var out = new LinkedHashMap<String, Long>();
        for (var e : new TreeMap<>(egressTo).entrySet()) out.put(e.getKey(), e.getValue().get());
        return out;
    }

    /** Charges bytes that left this zone to the region they went to. */
    void egress(String region, long bytes) {
        crossZoneBytes.addAndGet(bytes);
        egressTo.computeIfAbsent(region, r -> new AtomicLong()).addAndGet(bytes);
    }
    public long losimBytes()   { return losimBytes.get(); }
    public long losimNanos()   { return losimNanos.get(); }
    public long losimStops() { return losimStops.get(); }
    public long handledCalls() { return handled.get(); }
    public long bytesOut()     { return bytesOut.get(); }
    public long bytesIn()      { return bytesIn.get(); }

    /** Charges one metered stop of losim's own work to this machine, and to the span it sat in. */
    @Override public void charge(long bytes, long nanos) {
        chargeTo(Telemetry.SPAN.get(), bytes, nanos);
    }

    void chargeTo(Telemetry.Span span, long bytes, long nanos) {
        long owed = nanos + Meter.UNSEEN_NANOS_PER_REGION;
        // Only what landed on this machine's own threads can be taken back off it.
        // An async response is delivered on the channel's executor, which is not a
        // machine at all — its bytes were never counted against one, so subtracting
        // them would credit the machine for work it never did.
        if (onOwnThread()) {
            losimBytes.addAndGet(bytes);
            losimStops.incrementAndGet();
        }
        losimNanos.addAndGet(owed);
        if (span != null) span.losimNanos.addAndGet(owed);
    }

    /** Whether the calling thread is one of this machine's own. */
    boolean onOwnThread() {
        long id = Thread.currentThread().threadId();
        for (long t : threadIds) if (t == id) return true;
        return false;
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

    /** Peers, so not this machine: a blocking call to itself would starve its own pool. */
    @Override public List<String> peersServing(String service) {
        return fleet.serving(service).stream().filter(n -> !n.equals(name)).toList();
    }

    /**
     * The channel a handler gets, made once and kept.
     *
     * <p>The machine owns it, so a handler never sees a lifetime. It is the same
     * channel the job's {@code Cluster} hands out for this peer — one per pair, with
     * one place that closes them.
     */
    @Override public io.grpc.Channel dial(String peer) {
        if (fleet.machine(peer) == null)
            throw new IllegalArgumentException("there is no machine called '" + peer
                    + "'; this fleet has " + String.join(", ", fleet.names()));
        return dialled.computeIfAbsent(peer, this::channelTo);
    }

    @Override public double clockMs() { return tel().now(); }

    // -------------------------------------------------------------------- disk

    /**
     * Takes a write, or refuses it.
     *
     * <p>A machine whose disk is full cannot take the write, so this throws and
     * the handler fails the way it would have failed for real. Recording the write
     * and carrying on would let a design that does not fit on its disks appear to
     * work — which is the same mistake as an out-of-memory that only prints a
     * warning.
     */
    @Override public void wroteDisk(long bytes) {
        long now = diskBytes.addAndGet(bytes);
        double usedMb = now / 1048576.0;
        if (usedMb <= diskCapMb) return;
        if (diskFullReported.compareAndSet(false, true))
            tel().event(name, "disk_full", "resource", "disk", "capMb", diskCapMb,
                        "demandMb", round(usedMb),
                        "cause", "the machine was asked to write more than it has");
        throw new IllegalStateException(name + " has no disk left: " + round(usedMb)
                + " MB written against a " + round(diskCapMb) + " MB volume");
    }

    /**
     * A declared wait, spent against the compressed clock.
     *
     * <p>Nothing more than {@code spend}: no span, and no {@code inflight}. Waiting
     * is not work — a machine backing off occupies no vCPU — so counting it as
     * occupancy would make an idle fleet look loaded. The event is what puts it on
     * the timeline, which is enough to tell a stretch of waiting from a stretch of
     * doing nothing.
     */
    @Override public void sleep(double refMs) {
        fleet.clock.spend(refMs);
    }

    public long diskBytes() { return diskBytes.get(); }

    // -------------------------------------------------------------------- heap

    /** Anything this machine holds that is not reachable from one of its services. */
    public void alsoHolds(Object o) { roots.add(o); }

    /** Walks now, regardless of cadence. The probe grid needs a figure at a known instant. */
    public Retained.Result measureRetained() {
        var r = Retained.of(roots, this::notMine);
        retainedBytes.set(r.bytes());
        peakRetainedBytes.updateAndGet(p -> Math.max(p, r.bytes()));
        overCap(r);
        return r;
    }

    /**
     * Whether what was just measured is more than this machine has.
     *
     * <p>Checked here rather than only on the sampler's cadence, because a machine
     * that fills up between the last walk and the end of the run has still filled up.
     * A reducer given its bucket in the closing moments is exactly that case, and
     * without this it would be reported as comfortably within a cap it had already
     * exceeded — the one direction an out-of-memory must never be wrong in.
     */
    private void overCap(Retained.Result r) {
        double usedMb = r.bytes() / 1048576.0;
        if (usedMb <= memoryCapMb || oomReported) return;
        oomReported = true;
        tel().event(name, "oom", "resource", "memory", "capMb", memoryCapMb,
                    "demandMb", round(usedMb), "objects", r.objects(),
                    "cause", "retained heap exceeded the machine");
        kill("out of memory");
    }

    /**
     * The most this machine was ever seen holding.
     *
     * <p>Kept as a counter rather than read back off the sampled series, because the
     * series is quantised to the precision a person reads — and a fit against
     * numbers rounded to a hundredth of a megabyte is a fit against the rounding.
     */
    public long peakRetainedBytes() { return peakRetainedBytes.get(); }

    public long retainedBytes() { return retainedBytes.get(); }

    private void walkHeapIfDue() {
        if (++sinceWalk < WALK_EVERY_TICKS) return;
        sinceWalk = 0;
        var r = Retained.of(roots, this::notMine);
        retainedBytes.set(r.bytes());
        peakRetainedBytes.updateAndGet(p -> Math.max(p, r.bytes()));
        overCap(r);
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
        into.put("frozen",     frozen() ? 1.0 : 0.0);
        into.put("degraded",   degraded);
        into.put("bytesInMb",  bytesIn.get() / 1048576.0);
        into.put("bytesOutMb", bytesOut.get() / 1048576.0);
    }

    /**
     * Gives the machine's name back, and waits until it really has.
     *
     * <p>The waiting is the point, and it is not tidiness. {@code shutdownNow} is
     * asynchronous: it asks the server to stop and returns, and the in-process
     * transport keeps the name registered until it actually has. One run per JVM
     * never notices, because the process exits. Two things here do.
     *
     * <p>A <b>restart</b> rebinds the same name immediately, so it does not race
     * occasionally — it loses outright. And the <b>probe grid</b> runs thirty
     * fleets back to back in one JVM, all of them with a machine called {@code m0},
     * so the thirty-first fails to bind a name the thirtieth has not finished
     * releasing. Both surfaced only under chaos, which is what made them worth
     * chasing rather than retrying: more machines dying means more servers
     * shutting down at once, so the race is wider. It was never about the killing.
     *
     * <p>Written once and called from both, because the restart path had its own
     * copy without the wait, and a race fixed in one of two identical places is a
     * race that has been made rarer rather than removed.
     */
    private boolean releaseName() {
        Server stopping = server;
        server = null;
        if (stopping == null) return true;
        stopping.shutdownNow();
        try { return stopping.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    /** Stops the machine for good: its channels, its name, and its pool. */
    void shutdown() {
        stopped = true;
        dialled.values().forEach(ManagedChannel::shutdownNow);
        dialled.clear();
        if (server != null) server.shutdownNow();
        // The pool is interrupted *before* the server is waited on, not after.
        // shutdownNow cancels the calls but not the threads running them, and a
        // handler parked in a cost sleep goes on parking — so the transport never
        // terminates, the wait times out, and the name stays bound. Under chaos
        // there are always such threads, which is why it only ever surfaced there.
        pool.shutdownNow();
        if (!releaseName())
            System.err.println("losim: " + name + " did not release its name in time");
    }

    static double round(double x) { return Math.round(x * 1000) / 1000.0; }
}
