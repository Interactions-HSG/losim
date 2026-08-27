package losim.runtime;

import io.grpc.Metadata;
import java.util.*;
import java.util.concurrent.*;
import losim.res.InstanceCatalog;
import losim.res.InstanceSpec;
import losim.time.Clock;
import losim.trace.Telemetry;

/**
 * A cluster in one JVM, on gRPC's own in-process transport.
 *
 * <p>No ports, no containers, no simulated scheduler and no virtual clock. The
 * machines run on real threads against a real wall clock, and losim shapes what
 * happens through gRPC's own extension points — one in-process server per
 * machine, one executor per machine sized to its vCPUs, and an interceptor on
 * each side of every call.
 *
 * <p>The student's system underneath is genuinely running: real stubs, real
 * marshalling, real allocation, real contention. losim is the layer on top that
 * slows it, breaks it and measures it.
 */
public final class Fleet implements AutoCloseable {

    /** Carries the caller's span across the wire, so causality survives the RPC boundary. */
    static final Metadata.Key<String> PARENT =
            Metadata.Key.of("losim-parent-span", Metadata.ASCII_STRING_MARSHALLER);

    final Telemetry tel;
    final Clock clock;
    final Net net;

    private final Map<String, Machine> machines = new ConcurrentHashMap<>();
    private final List<String> order = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> byService = new ConcurrentHashMap<>();
    private volatile ExecutorService waiting;
    private volatile List<Retry> retries = List.of();

    public Fleet(Telemetry tel) { this(tel, new Net(0)); }

    public Fleet(Telemetry tel, Net net) {
        this.tel = tel;
        this.clock = tel.clock();
        this.net = net;
    }

    /**
     * Installs the scenario's retry policies.
     *
     * <p>Checked against what the fleet actually serves before anything runs: a
     * policy naming a method no machine offers, or retrying one the schema does not
     * declare safe, is refused with the line it was written on rather than
     * discovered as a duplicate write in a trace.
     */
    public Fleet retrying(List<Retry> policies) {
        var known = new ArrayList<io.grpc.MethodDescriptor<?, ?>>();
        for (Machine m : all()) known.addAll(m.methods());
        for (Retry r : policies) r.checkAgainst(known);
        this.retries = List.copyOf(policies);
        return this;
    }

    List<Retry> retries() { return retries; }

    public Telemetry telemetry() { return tel; }
    public Clock clock()         { return clock; }
    public Net net()             { return net; }

    // ----------------------------------------------------------------- machines

    /** A machine at its catalogue size. Scaled mode overrides the caps (D6). */
    public Machine machine(String name, String instanceType, String zone) {
        InstanceSpec spec = InstanceCatalog.get(instanceType);
        return machine(name, instanceType, zone, spec.memoryMb(), spec.storageGb() * 1024.0);
    }

    /**
     * A machine with caps set explicitly.
     *
     * <p>This is the form scaled mode uses: the caps come from the scaler engine,
     * which <i>solves</i> for them per resource rather than dividing them all by
     * one factor. Fixed overhead is real at every scale and stays full size; only
     * the variable part shrinks.
     */
    public Machine machine(String name, String instanceType, String zone,
                           double memoryCapMb, double diskCapMb) {
        if (machines.containsKey(name))
            throw new IllegalArgumentException("there is already a machine called '" + name + "'");
        var m = new Machine(this, name, InstanceCatalog.get(instanceType), zone,
                            memoryCapMb, diskCapMb);
        machines.put(name, m);
        order.add(name);
        tel.register(m);
        return m;
    }

    public Machine machine(String name) { return machines.get(name); }

    public List<String> names() { return List.copyOf(order); }

    public Collection<Machine> all() {
        var out = new ArrayList<Machine>(order.size());
        for (String n : order) out.add(machines.get(n));
        return out;
    }

    // ---------------------------------------------------------------- discovery

    /**
     * Records that a machine offers a service.
     *
     * <p>Peers are found by what they serve, never by hostname — which is the
     * only form of discovery that survives a machine being killed and replaced.
     */
    void offers(String fullServiceName, String machineName) {
        String bare = fullServiceName.substring(fullServiceName.lastIndexOf('.') + 1);
        register(bare, machineName);
        register(fullServiceName, machineName);
    }

    /**
     * Records the offer once, however many times it is made.
     *
     * <p>It is made more than once, twice over. A machine's server is rebuilt from
     * scratch every time a service is added to it, so a machine offering three
     * services announces the first one three times; and a machine that dies and
     * restarts announces everything it serves all over again. Neither is a mistake
     * in the caller — both are the server being rebuilt, which is what a restart is.
     *
     * <p>Unguarded it is a quiet disaster rather than a loud one. Nothing fails: a
     * peer simply appears twice in {@code serving()}, so a coordinator that fans out
     * one task per peer does the same work twice on the same machine, and every
     * count derived from the fleet's shape is wrong by a factor nobody chose. The
     * fleet is a set, so this is where it becomes one.
     */
    private void register(String key, String machineName) {
        var list = byService.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        if (!list.contains(machineName)) list.add(machineName);
    }

    /** Machines serving a named service, live ones first and dead ones not at all. */
    public List<String> serving(String service) {
        var found = byService.get(service);
        if (found == null) return List.of();
        return found.stream().filter(n -> {
            Machine m = machines.get(n);
            return m != null && m.alive;
        }).toList();
    }

    // ------------------------------------------------------------------ running

    /**
     * The run begins here.
     *
     * <p>Zeroes the clock, so that an instant written in the scenario is the same
     * instant in the trace, and announces the fleet. Everything before this — the
     * servers starting, the pools filling — is setup, and belongs to no scenario.
     */
    public Fleet begin() {
        clock.restart();
        for (Machine m : all()) m.announceBoot();
        return this;
    }

    /** Starts the sampler. The cadence follows the run's expected duration, not its busyness. */
    public void startSampling(double expectedRunMs) { tel.startSampling(expectedRunMs, 1000); }

    public void stopSampling() { tel.stopSampling(); }

    /** Where a dropped call waits out its deadline when the call carries no executor. */
    ExecutorService waiting() {
        ExecutorService w = waiting;
        if (w == null) synchronized (this) {
            w = waiting;
            if (w == null) waiting = w = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "losim-waiting");
                t.setDaemon(true);
                return t;
            });
        }
        return w;
    }

    @Override public void close() {
        tel.stopSampling();
        for (Machine m : all()) m.shutdown();
        ExecutorService w = waiting;
        if (w != null) w.shutdownNow();
    }
}
