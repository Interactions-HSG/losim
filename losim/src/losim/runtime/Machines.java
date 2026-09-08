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
public final class Machines implements AutoCloseable {

    /** Carries the caller's span across the wire, so causality survives the RPC boundary. */
    static final Metadata.Key<String> PARENT =
            Metadata.Key.of("losim-parent-span", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Marks the one call nobody in the system made: losim's own, to
     * {@code losim.Job}.
     *
     * <p>What it buys is the byte count. Every other call has a caller who
     * marshalled the request and was charged for it, and the invariant that makes
     * the accounting checkable is that what the callers sent is exactly what the
     * servers received. This call has no caller — and its argument is a
     * {@code Workload} the entry node built itself, moments earlier, in
     * {@code Load}. Counting it as bytes received would bill a node for ingress on
     * its own output, and for a Job whose Load builds something large it would bill
     * it a great deal.
     */
    static final Metadata.Key<String> OUTSIDE =
            Metadata.Key.of("losim-outside", Metadata.ASCII_STRING_MARSHALLER);

    final Telemetry tel;
    final Clock clock;
    final Net net;
    final long seed;

    /**
     * Which of {@code losim.Job}'s rpcs this simulation has already been entered
     * through.
     *
     * <p>Here rather than on the entry {@link Machine} because a restart rebuilds a
     * machine and everything on it, which would clear the record in exactly the
     * case it is wanted: the entry node is killed, comes back, and something calls
     * {@code Run} on the fresh instance.
     */
    private final Set<String> entered = ConcurrentHashMap.newKeySet();

    /** True the first time this simulation is entered through {@code method}. */
    boolean firstEntry(String method) { return entered.add(method); }

    private final Map<String, Machine> machines = new ConcurrentHashMap<>();
    private final List<String> order = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> byService = new ConcurrentHashMap<>();
    private volatile ExecutorService waiting;
    private volatile List<Retry> retries = List.of();
    private volatile Map<String, Cost> costs = Map.of();

    public Machines(Telemetry tel) { this(tel, new Net(0), 0); }

    public Machines(Telemetry tel, Net net) { this(tel, net, 0); }

    public Machines(Telemetry tel, Net net, long seed) {
        this.tel = tel;
        this.clock = tel.clock();
        this.net = net;
        this.seed = seed;
    }

    /**
     * Installs the scenario's retry policies.
     *
     * <p>Checked against what the cluster actually serves before anything runs: a
     * policy naming a method no machine offers, or retrying one the schema does not
     * declare safe, is refused with the line it was written on rather than
     * discovered as a duplicate write in a trace.
     */
    public Machines retrying(List<Retry> policies) {
        var known = new ArrayList<io.grpc.MethodDescriptor<?, ?>>();
        for (Machine m : all()) known.addAll(m.methods());
        for (Retry r : policies) r.checkAgainst(known);
        this.retries = List.copyOf(policies);
        return this;
    }

    List<Retry> retries() { return retries; }

    /**
     * Installs what each rpc costs, keyed by the class that serves it.
     *
     * <p>Checked against what the cluster actually runs, because a table is not an
     * annotation: rename an rpc and the number stops belonging to anything, and a
     * cost that costs nothing is a run that comes out fast, confident and wrong.
     * So a key naming a class no machine runs, or an rpc that class does not
     * serve, is refused with the line it was written on.
     *
     * <p>Kept here rather than pushed once into the machines, because a machine
     * that is killed and restarted rebuilds its services from scratch and has to
     * be able to ask again what they cost.
     */
    public Machines costing(Map<String, Cost> declared) {
        var known = new ArrayList<String>();
        var classes = new ArrayList<String>();
        for (Machine m : all()) {
            for (String placed : m.placed()) if (!classes.contains(placed)) classes.add(placed);
            for (String key : m.costKeys()) if (!known.contains(key)) known.add(key);
        }
        for (var e : declared.entrySet()) {
            if (known.contains(e.getKey())) continue;
            String klass = e.getKey().substring(0, Math.max(0, e.getKey().lastIndexOf('.')));
            throw new IllegalArgumentException(e.getValue().where()
                    + ": simulatedDuration names '"
                    + e.getKey().replace('.', ' ').trim() + "', and "
                    + (classes.contains(klass)
                        ? klass + " serves no rpc of that name. It serves "
                          + named(known, klass) + "."
                        : "no machine in this cluster runs " + (klass.isEmpty() ? "that" : klass)
                          + ". This cluster runs " + (classes.isEmpty() ? "nothing"
                                                    : String.join(", ", classes)) + ".")
                    + " A cost that belongs to nothing is a method that quietly takes no time,"
                    + " so it is a refusal rather than a warning.");
        }
        // The same question, asked of the same list, in the same pass — because a
        // failures: block naming an rpc nothing serves is the same typo as a
        // duration doing it, and two checks in two places are two checks that
        // drift. A failure that belongs to nothing never fires, and a call path
        // that was supposed to be unreliable and quietly was not is the worst
        // shape of wrong: it reads as a design that handled it.
        for (Machine m : all()) {
            for (var f : m.failureKeys().entrySet()) {
                if (known.contains(f.getKey())) continue;
                String klass = f.getKey().substring(0, Math.max(0, f.getKey().lastIndexOf('.')));
                throw new IllegalArgumentException(f.getValue() + ": failures names '"
                        + f.getKey().replace('.', ' ').trim() + "', and " + klass
                        + " serves no rpc of that name. It serves " + named(known, klass) + "."
                        + " A failure that belongs to nothing is a failure that quietly never"
                        + " fires, so it is a refusal rather than a warning.");
            }
        }

        this.costs = Map.copyOf(declared);
        for (Machine m : all()) m.recost();
        // Not a refusal: a method nobody has timed takes no time on purpose, and a
        // cluster where that is true of every method is a legitimate thing to run —
        // it is simply not a thing to read a timeline off.
        if (declared.isEmpty() && !known.isEmpty()) {
            tel.event("-", "note", "text", "nothing in this simulation declares what an rpc"
                    + " takes, so every call is instant: no queueing, no contention and no"
                    + " critical path. Add a simulatedDuration: block.");
        }
        return this;
    }

    /** The rpcs one placed class serves, for a refusal to list. */
    private static String named(List<String> known, String klass) {
        var mine = known.stream().filter(k -> k.startsWith(klass + "."))
                .map(k -> k.substring(klass.length() + 1)).sorted().toList();
        return mine.isEmpty() ? "none" : String.join(", ", mine);
    }

    Map<String, Cost> costs() { return costs; }

    /** The scenario's seed, so a machine can hand it to a handler that generates data. */
    public long seed() { return seed; }

    /**
     * A stream of draws belonging to one named thing, from this simulation's seed.
     *
     * <p><b>Not {@code new Random(seed + index)}.</b> {@link java.util.Random}
     * scrambles its seed by xor-ing one constant and then reads the high bits of a
     * linear congruential step, so seeds that differ in their low bits produce
     * first draws that differ in the sixth decimal place: 38 through 43 all begin
     * 0.7279. Six nodes seeded that way are not six streams, they are one stream
     * copied six times — and a pool where every node fails at the same instant
     * looks exactly like a correlated failure, which is a finding somebody would
     * go looking for the cause of.
     *
     * <p>So the name is hashed and the whole thing put through splitmix64's
     * finalizer first, which is what makes two adjacent inputs unrelated.
     */
    static java.util.Random stream(long seed, String what) {
        long z = seed * 0x9E3779B97F4A7C15L + what.hashCode();
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return new java.util.Random(z ^ (z >>> 31));
    }

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
     * count derived from the cluster's shape is wrong by a factor nobody chose. The
     * cluster is a set, so this is where it becomes one.
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
     * instant in the trace, and announces the cluster. Everything before this — the
     * servers starting, the pools filling — is setup, and belongs to no scenario.
     */
    public Machines begin() {
        clock.restart();
        for (Machine m : all()) m.announceBoot();
        return this;
    }

    /** Starts the sampler. It thins as the run goes on rather than being sized in advance. */
    public void startSampling() { tel.startSampling(); }

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
