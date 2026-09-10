package dissaly.runtime;

import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import dissaly.api.Bound;
import dissaly.res.InstanceSpec;
import dissaly.res.Meter;
import dissaly.res.Retained;
import dissaly.trace.Telemetry;
import dissaly.trace.Values;

/**
 * A simulated node with its own gRPC server, worker threads, and resource counters.
 *
 * <p>The worker pool size is the instance vCPU count. Worker ownership also
 * provides the attribution boundary for {@code getThreadAllocatedBytes}.
 *
 * <p>Handlers must not use {@code directExecutor()}, and workers must be platform
 * threads because {@code getThreadAllocatedBytes} is unavailable for virtual
 * threads.
 */
public final class Machine implements Bound, Telemetry.Sampled {

    /** Heap walks cost ~0.06 µs an object, so a busy machine is not walked every tick. */
    private static final int WALK_EVERY_TICKS = 8;

    private final Machines machines;
    final String name, zone;

    /**
     * The machine region, resolved once from its zone.
     *
     * <p>Once, and here, because a machine does not move and the alternative is
     * parsing a zone name on every call — on the caller's own thread, where it
     * would be charged to the student's program and land in the fixed term the
     * scale engine fits laws on. dissaly's own work is metered and taken back off
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
    /**
     * A service factory and its simulation source location.
     *
     * @param service what the {@code runs:} key filed it under, or null when
     *                nothing filed it — a cluster built in code names no service,
     *                so there is nothing to disagree with
     * @param named   what costs and failures are keyed by: the path, for a node
     *                built from a simulation
     */
    private record Offered(java.util.function.Supplier<? extends BindableService> factory,
                           String service,
                           String named,
                           java.util.Map<String, List<dissaly.sim.Simulation.RpcFailure>> failures,
                           String where) {}

    private final List<Offered> factories = new CopyOnWriteArrayList<>();
    private volatile boolean rebuildable;
    private final List<io.grpc.MethodDescriptor<?, ?>> served = new CopyOnWriteArrayList<>();

    final ThreadPoolExecutor pool;
    private final long[] threadIds;
    private final long allocAtBoot;
    private Server server;

    /**
     * Immutable node information exposed to services.
     *
     * <p>Built once here rather than per call: every field it holds is already
     * final, so there is nothing to measure and nothing to charge anyone for.
     */
    private final dissaly.api.Spec here;

    volatile boolean alive = true;

    /**
     * Set when the machine shuts down and never cleared.
     *
     * <p>A fault scheduled inside the run's horizon can still be in flight when the
     * run ends. The dispatcher is stopped before the cluster is torn down, which is
     * the tidy half, but stopping the thing that schedules failures does not stop a
     * fault already running — so a {@code restart_after} could land after every
     * machine had given its name back, and bind this one's name into a cluster that
     * no longer exists. Nobody would ever release it, and the <i>next</i> cluster in
     * the same JVM would find the name taken.
     *
     * <p>This is likeliest under weather: thirty systems running back to back in one
     * JVM, every one of them with a machine called {@code m0}, and enough
     * scheduled restarts for one to fall off the end.
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
    /** The last allocation total that could actually be read, net of the boot baseline. */
    private final AtomicLong lastGoodRaw = new AtomicLong();
    /** The largest program allocation seen, because a cumulative figure must not fall. */
    private final AtomicLong allocHighWater = new AtomicLong();
    /** Whether the loss of this machine's allocation meter has been reported yet. */
    private final AtomicBoolean meterLost = new AtomicBoolean();
    final AtomicLong peakRetainedBytes = new AtomicLong();

    /** Bytes that left this machine for another zone, which is the traffic anyone pays for. */
    final AtomicLong crossZoneBytes = new AtomicLong();

    /**
     * Cross-zone bytes grouped by destination region.
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

    // dissaly's own measured work, removed from user totals.
    final AtomicLong dissalyBytes   = new AtomicLong();
    final AtomicLong dissalyNanos   = new AtomicLong();
    final AtomicLong dissalyStops = new AtomicLong();

    private final List<Object> roots = new CopyOnWriteArrayList<>();

    /**
     * What every service on this machine can see, and no other machine can.
     *
     * <p>Emptied in {@link #start()}, which is where a rebuildable machine gets
     * fresh service instances — so the store is lost exactly when the fields are,
     * and a restart means one thing rather than two.
     */
    private final java.util.concurrent.ConcurrentMap<String, Object> store =
            new ConcurrentHashMap<>();
    private final Map<String, Cost> declared = new ConcurrentHashMap<>();
    private final Map<String, String> runsAs = new ConcurrentHashMap<>();
    /**
     * What goes wrong with each rpc here, by full method name, with the stream
     * each one is drawn from.
     *
     * <p>Rebuilt in {@link #start()} alongside the services, so a node that comes
     * back draws the same sequence it drew the first time. That is deliberate: a
     * restart is a fresh process, and a bad replica that came back good would be
     * a fix nobody applied.
     */
    private final Map<String, List<Drawn>> failing = new ConcurrentHashMap<>();
    /** Every `named.Rpc` a failures: block declared here, and the line it was on. */
    private final Map<String, String> failureWhere = new ConcurrentHashMap<>();
    private final Map<String, ManagedChannel> dialled = new ConcurrentHashMap<>();
    private final List<String> servicesOffered = new CopyOnWriteArrayList<>();
    private int sinceWalk = WALK_EVERY_TICKS;            // walk on the very first tick

    /** One declared rpc failure and the stream it fires from. */
    record Drawn(dissaly.sim.Simulation.RpcFailure spec, java.util.Random rng) {
        /** Whether this call is the one in {@code perCalls}. */
        boolean fires() {
            synchronized (rng) { return rng.nextInt(spec.perCalls()) == 0; }
        }
    }
    private volatile boolean oomReported;
    private final java.util.concurrent.atomic.AtomicBoolean diskFullReported =
            new java.util.concurrent.atomic.AtomicBoolean();

    Machine(Machines machines, String name, InstanceSpec spec, String zone,
            double memoryCapMb, double diskCapMb) {
        this.machines = machines;
        this.name = name;
        this.spec = spec;
        this.zone = zone;
        this.region = dissaly.res.Regions.regionOf(zone);
        this.vcpu = (int) Math.max(1, Math.round(spec.vcpu()));
        this.memoryCapMb = memoryCapMb;
        this.diskCapMb = diskCapMb;
        this.machineFactor = spec.cpuFactor();
        this.here = new dissaly.api.Spec(name, spec.name(), zone, vcpu, memoryCapMb, diskCapMb);

        String threadName = "dissaly-" + name + "-w";
        this.pool = new ThreadPoolExecutor(vcpu, vcpu, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> { Thread t = new Thread(r, threadName); t.setDaemon(true); return t; });
        pool.prestartAllCoreThreads();

        var ids = new ArrayList<Long>();
        for (Thread t : Thread.getAllStackTraces().keySet())
            if (t.getName().equals(threadName)) ids.add(t.threadId());
        this.threadIds = ids.stream().mapToLong(Long::longValue).toArray();
        // −1 means the meter could not be read at all; 0 is the honest baseline
        // to fall back on, and `rawAllocatedBytes` reports the loss either way.
        long boot = Meter.allocatedBy(threadIds);
        this.allocAtBoot = boot < 0 ? 0 : boot;
    }

    Machines machines() { return machines; }
    Telemetry tel() { return machines.tel; }
    public String name() { return name; }
    @Override public dissaly.api.Spec here() { return here; }
    public int vcpu()    { return vcpu; }
    public String instance() { return spec.name(); }
    public String zone()     { return zone; }

    /** Which region its zone is in — what decides the price of talking to it. */
    public String region()   { return region; }
    public boolean alive() { return alive; }

    /**
     * Zeroes every counter, as though this node had done nothing yet.
     *
     * <p>For the one thing that happens on a node and is charged to nobody:
     * {@code dissaly.Job/Load}, which reads the input before the simulation starts.
     * Reading a file is not part of the design being measured, so what it spent is
     * taken back rather than subtracted later — the ledger is the thing every
     * figure in the trace is read off, and a correction applied afterwards is a
     * correction somebody has to remember to apply.
     */
    void forget() {
        bytesIn.set(0);
        bytesOut.set(0);
        handled.set(0);
        diskBytes.set(0);
        crossZoneBytes.set(0);
        egressTo.clear();
        dissalyBytes.set(0);
        dissalyNanos.set(0);
        dissalyStops.set(0);
        // Not `retained`: what the node is holding it is still holding, and a
        // Workload that Load built and Run has yet to see is genuinely in memory.
        // The high-water mark is reset, because the peak of a run should be a peak
        // the run reached.
        peakRetainedBytes.set(retainedBytes.get());
        allocHighWater.set(0);
    }
    public double memoryCapMb() { return memoryCapMb; }

    // ------------------------------------------------------------------ serving

    /**
     * Registers services and starts listening.
     *
     * <p>The instances are kept as they are, so a machine that dies and comes back
     * comes back <i>remembering</i>. That is usually not what a restart means, and
     * the restart event says so. Use {@link #serves(java.util.function.Supplier)}
     * to have dissaly build fresh ones.
     */
    public Machine serving(BindableService... services) {
        // Unnamed: nothing placed these by a name, so start() takes their own.
        for (BindableService svc : services)
            factories.add(new Offered(() -> svc, null, null, java.util.Map.of(), ""));
        return start();
    }

    /**
     * Registers a service dissaly can rebuild.
     *
     * <p>A restarted machine gets a new instance, so whatever the old one was
     * holding is gone — which is what a restart is, and what makes "who redoes the
     * work" a real question rather than a formality.
     */
    public Machine serves(java.util.function.Supplier<? extends BindableService> factory) {
        return serves(factory, null, "");
    }

    /**
     * The same, knowing the line of the simulation that asked for it.
     *
     * <p>What this machine can and cannot serve is decided here, when the service
     * is bound and its methods are known — the loader cannot do it, because it has
     * not loaded a class. So a refusal from here carries the {@code runs:} line it
     * belongs to, and reads like every other one the loader gives.
     */
    public Machine serves(java.util.function.Supplier<? extends BindableService> factory,
                          String where) {
        return serves(factory, null, where);
    }

    /**
     * The same, under the name the simulation placed it by.
     *
     * <p>That name is how {@code simulatedDuration:} finds it: a cost is written under what
     * {@code runs:} says, so the two spell one thing one way.
     */
    public Machine serves(java.util.function.Supplier<? extends BindableService> factory,
                          String named, String where) {
        return serves(factory, named, java.util.Map.of(), where);
    }

    /**
     * The same, with what the simulation said goes wrong with this service here.
     *
     * <p>Keyed by rpc, and held per node rather than per service class, because
     * that is the whole point of writing it inside {@code runs:}: the same service
     * can be the bad replica on one node and fine on its peers, and a design that
     * routes around it is a different design from one that does not.
     */
    public Machine serves(java.util.function.Supplier<? extends BindableService> factory,
                          String named,
                          java.util.Map<String, List<dissaly.sim.Simulation.RpcFailure>> failures,
                          String where) {
        return serves(factory, null, named, failures, where);
    }

    /**
     * The same, under the service name the simulation filed it under.
     *
     * <p>Which is checked against what the class actually serves, once it is bound
     * and can be asked. The loader cannot ask: it loads nothing. And the key is not
     * decoration — it is the name peers find this node by — so a key no server
     * answers to is a node that quietly serves something else than the file says.
     */
    public Machine serves(java.util.function.Supplier<? extends BindableService> factory,
                          String service,
                          String named,
                          java.util.Map<String, List<dissaly.sim.Simulation.RpcFailure>> failures,
                          String where) {
        factories.add(new Offered(factory, service, named, failures, where));
        rebuildable = true;
        return start();
    }

    /**
     * Whether dissaly can put a number on this method, or must refuse it.
     *
     * <p>Two things it cannot price, and both would be silent. A <b>streaming</b>
     * rpc is metered by a cost model that assumes one request and one response:
     * {@code refNsPerUnit} is slept inside {@code sendMessage}, so it is paid
     * once per message rather than once per call, and {@code refMs} is paid at
     * half-close, which for a client-streaming or bidirectional handler is after
     * the work it stands for. A <b>non-protobuf marshaller</b> has no serialized
     * size, and {@link Wire#sizeOf} answers 0 for it, so every call weighs nothing
     * and the bill says so.
     *
     * <p>Refused rather than flagged, which is the opposite of what the verifier
     * does and deliberately so. The verifier's rules yield a wrong number beside a
     * marker saying it is wrong; these two yield a wrong number that looks right,
     * and a projection fitted to one is smooth, confident and false.
     */
    private static void priceable(io.grpc.MethodDescriptor<?, ?> md, String where) {
        String at = where.isEmpty() ? "" : where + ": ";
        String method = Wire.dotted(md.getFullMethodName());
        if (md.getType() != io.grpc.MethodDescriptor.MethodType.UNARY)
            throw new IllegalArgumentException(at + method + " is a "
                    + md.getType().name().toLowerCase().replace('_', '-') + " rpc, and dissaly"
                    + " prices a call as one request and one response: a declared"
                    + " refNsPerUnit would be slept once per message sent, and refMs paid"
                    + " when the client stopped sending rather than before the handler ran."
                    + " The numbers would come out consistent and wrong. Make it unary.");
        if (!protobuf(md.getRequestMarshaller()) || !protobuf(md.getResponseMarshaller()))
            throw new IllegalArgumentException(at + method + " carries a marshaller of its"
                    + " own rather than a protobuf one. Every byte dissaly counts is the"
                    + " serialized size of a protobuf message, so this call would weigh"
                    + " nothing on the wire and cost nothing in the bill — which is worse"
                    + " than refusing it.");
    }

    /**
     * Whether this marshaller hands back something {@link Wire#sizeOf} can weigh.
     *
     * <p>The marshaller and not the schema descriptor: dissaly builds one
     * {@code MethodDescriptor} of its own by hand, for the warm-up, and it carries
     * no schema descriptor while being perfectly ordinary protobuf. What the byte
     * count actually depends on is whether the message is a {@link Message}, and a
     * {@code PrototypeMarshaller} is the one place that says so before a call.
     */
    private static boolean protobuf(io.grpc.MethodDescriptor.Marshaller<?> m) {
        return m instanceof io.grpc.MethodDescriptor.PrototypeMarshaller<?> p
                && p.getMessageClass() != null
                && com.google.protobuf.Message.class.isAssignableFrom(p.getMessageClass());
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
        // A root, so what the store holds counts against the memory cap like
        // anything a service holds in a field. Emptied first: this is the same
        // moment a rebuildable machine gets fresh services, and a machine that came
        // back remembering its cache while forgetting its fields would be neither
        // a restart nor a survival.
        store.clear();
        roots.add(store);
        declared.clear();
        served.clear();
        failing.clear();
        failureWhere.clear();
        var b = InProcessServerBuilder.forName(name).executor(queueing);
        runsAs.clear();
        for (var offered : factories) {
            BindableService s = offered.factory().get();
            roots.add(s);                                // a machine's data hangs off its services
            // What the simulation called this, so `simulatedDuration:` can be looked up under the
            // same word the person wrote. Falling back to the class's own name for
            // a cluster built in code, which named nothing.
            String named = offered.named() != null ? offered.named() : nameOf(s.getClass());
            var def = s.bindService();
            for (var m : def.getMethods()) {
                priceable(m.getMethodDescriptor(), offered.where());
                served.add(m.getMethodDescriptor());
                String full = m.getMethodDescriptor().getFullMethodName();
                runsAs.put(full, named);
                var mine = offered.failures().get(rpcOf(full));
                if (mine != null && !mine.isEmpty()) {
                    var drawn = new java.util.ArrayList<Drawn>();
                    for (var f : mine)
                        // Seeded from the simulation, the node and the rpc, so two
                        // nodes running one service fail on different calls — which
                        // is what makes one of them the bad replica.
                        drawn.add(new Drawn(f, Machines.stream(machines.seed(),
                                name + '/' + full + '/' + f.kind())));
                    failing.put(full, List.copyOf(drawn));
                }
            }
            // Recorded whether or not the rpc exists: an rpc this class does not
            // serve is exactly the typo `Machines.costing` is about to refuse, and
            // it can only refuse what it was told was written down.
            for (var f : offered.failures().entrySet())
                failureWhere.put(named + "." + f.getKey(),
                        f.getValue().isEmpty() ? offered.where() : f.getValue().get(0).where());
            String svc = def.getServiceDescriptor().getName();
            String bare = svc.substring(svc.lastIndexOf('.') + 1);
            // Either form of the name reaches the same server, and a simulation may
            // write either. Anything else is a typo that would cost nobody an error
            // and every peer a lookup: peersServing returns an empty list, and a
            // design that handles an absent peer would report handling one.
            if (offered.service() != null
                    && !offered.service().equals(svc) && !offered.service().equals(bare))
                throw new IllegalArgumentException(offered.where() + ": this node runs '"
                        + offered.named() + "' under '" + offered.service() + "', and the"
                        + " class in it serves " + svc + ". A runs: key is the name peers"
                        + " find this node by, so peersServing('" + offered.service() + "')"
                        + " would find nobody and a design that copes with a missing peer"
                        + " would look like one that worked.");
            if (!servicesOffered.contains(bare)) servicesOffered.add(bare);
            machines.offers(svc, name);
            b.addService(ServerInterceptors.intercept(def, new ServerSide(this)));
        }
        recost();
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
     * <p>Held back until the run's clock starts, so a cluster's booting does not
     * appear to have happened before the run it belongs to.
     */
    void announceBoot() {
        announced = true;
        tel().event(name, "boot", "instance", spec.name(), "zone", zone, "vcpu", vcpu,
                    "memoryMb", memoryCapMb, "diskMb", diskCapMb);
    }

    private volatile boolean announced;

    // ------------------------------------------------------------------ failures

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
            machines.clock.parkRealNanos(Math.min(left, 2_000_000));
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
    /** How many times this node has been restarted, for a refusal that has to say so. */
    public int restarts() { return restarts.get(); }

    private final java.util.concurrent.atomic.AtomicInteger restarts =
            new java.util.concurrent.atomic.AtomicInteger();

    public void restart() {
        restarts.incrementAndGet();
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

    Cost takenBy(String fullMethodName) { return declared.get(fullMethodName); }

    /**
     * Re-reads the simulation's costs, for the methods this machine now serves.
     *
     * <p>Called when the cluster is told what things cost and again whenever this
     * machine rebuilds its services, because a machine that was killed and came
     * back with fresh handlers would otherwise come back free.
     */
    void recost() {
        declared.clear();
        for (io.grpc.MethodDescriptor<?, ?> md : served) {
            String full = md.getFullMethodName();
            Cost c = machines.costs().get(runsAs.getOrDefault(full, "") + "." + rpcOf(full));
            if (c != null) declared.put(full, c);
        }
    }

    /**
     * What to call a service nobody named — a cluster built in code rather than from
     * a simulation.
     *
     * <p>Its own class, unless that is an anonymous one: {@code new VolleyBase(){…}}
     * has no simple name at all, and a cost keyed on the empty string would belong
     * to nothing and be refused for it. The base it extends is the nearest thing to
     * a name such a service has.
     */
    private static String nameOf(Class<?> k) {
        for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
            if (!c.getSimpleName().isEmpty()) return c.getSimpleName();
        }
        return k.getName();
    }

    /** The rpc's own name, as the schema spells it: {@code dissaly.t.Worker/Map} -> {@code Map}. */
    static String rpcOf(String fullMethodName) {
        return fullMethodName.substring(fullMethodName.indexOf('/') + 1);
    }

    /** What each served method's class was placed under, for looking its cost up. */
    List<String> placed() { return List.copyOf(new java.util.LinkedHashSet<>(runsAs.values())); }

    /**
     * Every {@code Class.Rpc} this machine could be given a cost for.
     *
     * <p>Built from the pairs rather than from the two lists, because a machine
     * running two services would otherwise accept `Reducer.Map` — every class
     * crossed with every rpc — and the check would stop catching the typo it
     * exists for.
     */
    /** Every `named.Rpc` a failures: block claimed here, with its line, for the refusal. */
    Map<String, String> failureKeys() { return Map.copyOf(failureWhere); }

    /**
     * What the simulation said goes wrong with this rpc here, drawn afresh.
     *
     * <p>Consulted once per call and per kind, on the thread the call is on, so a
     * draw belongs to a call rather than to a moment.
     */
    List<Drawn> failuresOn(String fullMethodName) {
        return failing.getOrDefault(fullMethodName, List.of());
    }

    List<String> costKeys() {
        var out = new java.util.ArrayList<String>();
        for (var e : runsAs.entrySet()) {
            String key = e.getValue() + "." + rpcOf(e.getKey());
            if (!out.contains(key)) out.add(key);
        }
        return out;
    }

    /**
     * Work this machine does that no RPC carried.
     *
     * <p>Without it a master merging locally is indistinguishable from one
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
     * A channel to a peer, with dissaly's client side already on it.
     *
     * <p>The retry interceptor sits outside the recording one, so every attempt is
     * a genuinely separate call rather than a repeat of the same accounting.
     */
    public ManagedChannel channelTo(String peer) {
        var b = InProcessChannelBuilder.forName(peer).usePlaintext();
        return machines.retries().isEmpty()
                ? b.intercept(new ClientSide(this, peer)).build()
                : b.intercept(new Retrying(this, machines.retries()), new ClientSide(this, peer)).build();
    }

    /** Every service this machine offers, by name — how peers find it, and what it is. */
    public List<String> servicesOffered() { return List.copyOf(servicesOffered); }

    /** Every method this machine serves. The retry gate is checked against these. */
    List<io.grpc.MethodDescriptor<?, ?>> methods() { return List.copyOf(served); }

    /**
     * Runs work on this machine's own threads, with the machine ambient.
     *
     * <p>This is how a master drives its own calls. It matters for more than
     * tidiness: work started on a thread the machine did not create carries no
     * machine identity, so its memory and CPU are attributed to nobody, and dissaly
     * can only flag it rather than count it (D11).
     */
    public java.util.concurrent.Future<?> submit(Runnable r) {
        return pool.submit(io.grpc.Context.current()
                .withValue(dissaly.api.Ambient.MACHINE, this).wrap(r));
    }

    public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> c) {
        return pool.submit(io.grpc.Context.current()
                .withValue(dissaly.api.Ambient.MACHINE, this).wrap(c));
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
     * <p>dissaly's own bookkeeping runs on these same threads, so it is metered
     * separately and taken back off. Reporting the sum would charge a machine for
     * being watched, and — worse — charge it more the more heavily its program is
     * instrumented (D13).
     */
    public long allocatedBytes() {
        long now = Math.max(0, rawAllocatedBytes() - dissalyBytes.get());
        // A cumulative number must not fall, and this one does, because `raw`
        // itself goes backwards. `getThreadAllocatedBytes`, read from another
        // thread, sums a thread's retired total and the used part of its current
        // TLAB, without reading the two together. A reader that samples the
        // total just before a TLAB is retired and the buffer just after sees up
        // to a whole TLAB vanish. Measured: the worst dip equals the TLAB size
        // (0.062 MB at `-XX:TLABSize=65536`, 0.999 MB at 1m, none at all under
        // `-XX:-UseTLAB`), and larger still when the reader is descheduled across
        // several refills.
        //
        // The problem sits below anything dissaly can fix by bookkeeping, so the
        // clamp is the fix. A program cannot un-allocate, so the honest reading
        // of a monotonic quantity measured by a racy counter is its high-water
        // mark. Without it the series goes backwards, and anyone plotting it as
        // a counter believes the trace is broken. The scaler is one of them: it
        // fits a law against this series.
        //
        // Only cross-thread reads are exposed. A thread reading its own counter
        // cannot be mid-refill while it asks, so every metered bracket in dissaly
        // is safe: 77 million self-reads under hard allocation produced no dip
        // and no negative bracket width.
        return allocHighWater.accumulateAndGet(now, Math::max);
    }

    /**
     * Before the subtraction. Only for measuring the observer effect itself.
     *
     * <p>Answers the last figure it was able to read when the meter cannot be
     * read now, rather than a number derived from {@code -1}. A machine loses a
     * thread when it is killed, and its allocation to that point is still true;
     * what would not be true is that it suddenly allocated nothing.
     */
    public long rawAllocatedBytes() {
        long total = Meter.allocatedBy(threadIds);
        if (total < 0) {
            // Said once, and said in the trace: the difference between "nothing"
            // and "cannot say" has to be visible to whoever reads the number.
            if (meterLost.compareAndSet(false, true) && machines != null && tel() != null)
                tel().event(name, "meter_lost", "resource", "allocation",
                        "cause", "a thread of this machine can no longer be read",
                        "lastKnownBytes", lastGoodRaw.get());
            return lastGoodRaw.get();
        }
        long raw = total - allocAtBoot;
        lastGoodRaw.set(raw);
        return raw;
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
    public long dissalyBytes()   { return dissalyBytes.get(); }
    public long dissalyNanos()   { return dissalyNanos.get(); }
    public long dissalyStops() { return dissalyStops.get(); }
    public long handledCalls() { return handled.get(); }
    public long bytesOut()     { return bytesOut.get(); }
    public long bytesIn()      { return bytesIn.get(); }

    /** Charges one metered stop of dissaly's own work to this machine, and to the span it sat in. */
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
            dissalyBytes.addAndGet(bytes);
            dissalyStops.incrementAndGet();
            // Also on the span when there is one, so the handler's own allocation
            // can have ours subtracted from it the way its duration already is.
            // `charge` reads the span from the ambient context, and the client
            // side meters work that precedes any call existing — so null here
            // is ordinary, not a mistake.
            if (span != null) span.dissalyBytes.addAndGet(bytes);
        }
        dissalyNanos.addAndGet(owed);
        if (span != null) span.dissalyNanos.addAndGet(owed);
    }

    /** Whether the calling thread is one of this machine's own. */
    boolean onOwnThread() {
        long id = Thread.currentThread().threadId();
        for (long t : threadIds) if (t == id) return true;
        return false;
    }

    // ---------------------------------------------------------------- the facade

    @Override public void event(String kind, Object... kv) { tel().event(name, kind, kv); }

    @Override public void units(long n) {
        Telemetry.Span s = Telemetry.SPAN.get();
        if (s != null) s.units.set(n);
    }

    @Override public List<String> peers() {
        return machines.names().stream().filter(n -> !n.equals(name)).toList();
    }

    /** Peers, so not this machine: a blocking call to itself would starve its own pool. */
    @Override public List<String> peersServing(String service) {
        return machines.serving(service).stream().filter(n -> !n.equals(name)).toList();
    }

    /**
     * The channel a handler gets, made once and kept.
     *
     * <p>The machine owns it, so a handler never sees a lifetime. It is the same
     * channel the job's {@code Cluster} hands out for this peer — one per pair, with
     * one place that closes them.
     */
    @Override public io.grpc.Channel dial(String peer) {
        if (machines.machine(peer) == null)
            throw new IllegalArgumentException("there is no node called '" + peer
                    + "'; this simulation has " + String.join(", ", machines.names()));
        return dialled.computeIfAbsent(peer, this::channelTo);
    }

    @Override public double clockMs() { return tel().now(); }

    @Override public long seed() { return machines.seed(); }

    @Override public java.util.concurrent.ConcurrentMap<String, Object> local() { return store; }

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
     * occupancy would make an idle cluster look loaded. The event is what puts it on
     * the timeline, which is enough to tell a stretch of waiting from a stretch of
     * doing nothing.
     */
    @Override public void sleep(double refMs) {
        machines.clock.spend(refMs);
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
     * <p>Its own plumbing is not its data; another machine's heap is
     * not; gRPC's transport is shared by everyone. But the protobuf messages it is
     * holding <i>are</i> exactly its data. dissaly's own structures sit outside every
     * machine's boundary, which is asserted rather than assumed (D13 rule 7).
     */
    private boolean notMine(Object o) {
        if (o instanceof Machine || o instanceof Machines || o instanceof Telemetry
            || o instanceof Telemetry.Span || o instanceof dissaly.time.Clock || o instanceof Net
            || o instanceof Server || o instanceof Channel
            || o instanceof Executor || o instanceof Thread) return true;
        String n = o.getClass().getName();
        return n.startsWith("io.grpc.")
            || n.startsWith("dissaly.trace.")
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
     * clusters back to back in one JVM, all of them with a machine called {@code m0},
     * so the thirty-first fails to bind a name the thirtieth has not finished
     * releasing. Both races are widest under weather: more nodes dying means more
     * servers shutting down at once. It is the concurrent shutdowns that widen the
     * race, not the killing itself.
     *
     * <p>Written once and called from both paths, because a race fixed in only
     * one of two duplicated call sites is a race made rarer, not removed.
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
        // terminates, the wait times out, and the name stays bound. Chaos runs
        // always have such threads in flight, which is where this matters most.
        pool.shutdownNow();
        if (!releaseName())
            System.err.println("dissaly: " + name + " did not release its name in time");
    }

    static double round(double x) { return Math.round(x * 1000) / 1000.0; }
}
