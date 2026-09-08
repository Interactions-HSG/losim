package losim.scenario;

import java.util.List;
import java.util.Map;
import losim.runtime.Cost;
import losim.runtime.Retry;

/**
 * A cluster, its weather and its bad afternoon — as data.
 *
 * <p>Nothing here is authored that the run could find out for itself. Anything that
 * needs code points at a class by name, so the file stays diffable, sweepable and
 * readable by someone who did not write it. That matters more than it sounds:
 * comparing two designs means comparing two of these, and a scenario that hides a decision in a script cannot be compared at
 * all.
 *
 * <p>Every duration is reference-machine time (D3), which is why they are
 * {@code double refMs} throughout and why the file has to say so.
 */
public record Scenario(
        String file,
        long seed,
        double scale,
        long units,
        /**
         * The workload: where it comes from, what one item is, and how many.
         *
         * <p>Three fields and no more. There is exactly one number the engine
         * varies, so there is exactly one number to write — the shape of the data
         * is the Job's business, declared in its own {@code .proto} and built by
         * its own {@code Load}, where a simulation has nothing to say about it.
         */
        InputSpec input,
        List<NodeSpec> nodes,
        NetSpec net,
        List<Retry> retries,
        /**
         * What each rpc costs, by dotted {@code Service.Method} name.
         *
         * <p>Here rather than on the handler, so that a student's Java names
         * losim nowhere: a system can be written, compiled and unit-tested with
         * this project off the classpath entirely. What that gives up is drift —
         * an annotation follows a renamed method and a table does not — which is
         * why a key naming a method the cluster does not serve is refused at load
         * with the line it was written on.
         */
        Map<String, Cost> simulatedDuration,
        Mode mode) {

    /**
     * The ladder the engine climbs, and the run it climbs to.
     *
     * <p>Fixed, and not a knob. Four rungs is the fewest that can show whether a law
     * bends, and a law that bends has to be refused rather than extrapolated across.
     * Which four is the engine's business: a scenario that could pick its own would
     * be a scenario that could pick a ladder its design happens to look linear on.
     */
    public static final List<Integer> LADDER = List.of(1000, 2000, 4000, 8000);

    /** The biggest run the engine can actually measure — the top of the ladder. */
    public static final long BASE = LADDER.get(LADDER.size() - 1);

    /** The compression a scenario gets when it asks for no scale at all. */
    public static final double BASE_COMPRESSION = 20;

    /** As fast as the clock is ever run, however large the scale. */
    public static final double MAX_COMPRESSION = 40;

    /**
     * Whether the run is the thing, or a measurement of it.
     *
     * <p>There are exactly two, and no third. In {@link #DIRECT} nothing is scaled
     * and nothing is inferred: every number on screen is what happened. In
     * {@link #SCALED} the scaler engine decides the run size, the cluster and every
     * cap, and projects the results back with error bars. A scenario cannot
     * hand-declare a shrink factor and bypass the engine — that would be a third
     * mode whose numbers nobody could account for.
     */
    public enum Mode { DIRECT, SCALED }

    /**
     * How fast the clock is run, in reference milliseconds per real millisecond.
     *
     * <p><b>Derived, never declared.</b> Nobody authoring a system knows what
     * compression it can express — that is a property of the smallest cost in it,
     * which is a thing the run finds out. So it follows the one number a student
     * does know: how much bigger the design is meant to be than the run measuring
     * it. More work per instant is more for a coarse clock to absorb, and the
     * engine stops raising it at {@link #MAX_COMPRESSION} because past that the
     * sleep debt stops settling and the run's own timings drift.
     */
    public double kTime() {
        return Math.min(MAX_COMPRESSION, BASE_COMPRESSION * Math.max(1, scale));
    }

    /**
     * The size this design is meant to handle.
     *
     * <p>Scale 1 is not a scale model of anything — it is the run itself, and the
     * work it does is exactly the {@code count:} the file wrote. That is what a
     * simulation saying nothing about scale gets, and it is how a full-size run is
     * written: no {@code scale:} key at all. Naming a scale and then asking for the
     * whole of it used to need a {@code mode:} saying so, which was a second way to
     * say what {@code scale:} already says, and the two could disagree.
     *
     * <p>Above 1 the scenario is a model of something bigger, and then the size is
     * {@code scale} times {@link #BASE} — the biggest run the engine can actually
     * measure. The step between the two is a step between kinds, not a slider: a
     * run and a model of a run are different claims, and only the second one has
     * error bars.
     */
    public long fullUnits() { return scale <= 1 ? 1 : Math.round(scale * BASE); }

    /**
     * How many nodes to put in each multi-machine pool, varied on its own.
     *
     * <p>Derived from the cluster that was drawn, because that is the cluster the
     * question is about: probing a design at two workers when it is written for
     * eight extrapolates across the very thing being measured. Varied independently
     * of the data so a resource can be attributed to the right variable rather than
     * to whichever one happened to move with it.
     */
    public List<Integer> workerCounts() {
        int declared = workers();
        return List.of(Math.max(2, declared / 2), Math.max(3, declared));
    }

    /**
     * How many workers this system has.
     *
     * <p><b>Not the node that runs {@code losim.Job}.</b> That one starts the work
     * and is not a worker, and counting it as one is not a rounding error: the data
     * ladder would be climbed on a cluster one node larger than the design, and
     * every per-node peak — disk, memory — would come out proportionally low. The
     * projection is then quietly optimistic about exactly the resources a design
     * runs out of.
     *
     * <p>It was invisible until 3.0 because the thing that started the work was a
     * driver object that ran on a node without being placed there, so a
     * coordinator's {@code runs:} was genuinely empty. Two places asked this
     * question and both got it wrong the same way; this is the only place that
     * asks it now.
     */
    public int workers() {
        return (int) Math.max(1, nodes.stream()
                .filter(m -> !m.runs().isEmpty())
                .filter(m -> !m.runs().containsKey(losim.pb.JobGrpc.SERVICE_NAME))
                .count());
    }

    /**
     * How many things this simulation says can go wrong, at both levels.
     *
     * <p>One number, because the model wants one: the amplification term asks how
     * much weather there is, not what shape it takes. A node killed on a rate and
     * an rpc that answers UNAVAILABLE one call in twenty both make a run longer
     * than a clean one, and the fit does not care which did it.
     */
    public int failureCount() {
        int n = 0;
        for (NodeSpec m : nodes) {
            n += m.failures().size();
            for (ServiceSpec svc : m.runs().values())
                for (var each : svc.failures().values()) n += each.size();
        }
        return n;
    }

    // ------------------------------------------------------------------ variants
    //
    // The probe grid needs the same scenario at many sizes, cluster shapes and seeds.
    // Producing those here rather than by editing files keeps one fact — what this
    // system is — in one place, and makes the grid's axes explicit.

    public Scenario withSeed(long seed) {
        return new Scenario(file, seed, scale, units, input, nodes,
                net, retries, simulatedDuration, mode);
    }

    /** The run size the engine solved for, replacing the full-scale one. */
    public Scenario withUnits(long n) {
        return new Scenario(file, seed, scale, n, input, nodes,
                net, retries, simulatedDuration, mode);
    }

    public Scenario withMode(Mode m) {
        return new Scenario(file, seed, scale, units, input, nodes,
                net, retries, simulatedDuration, m);
    }

    /**
     * The same simulation with no weather at all — the clean column of the grid.
     *
     * <p>Both levels, because both are weather: a node that is killed on a rate
     * and an rpc that answers UNAVAILABLE one call in twenty are the same kind of
     * thing written in two places, and a clean column that still had one of them
     * would be measuring a system nobody declared.
     */
    public Scenario withoutWeather() {
        var out = new java.util.ArrayList<NodeSpec>();
        for (NodeSpec m : nodes) {
            var runs = new java.util.LinkedHashMap<String, ServiceSpec>();
            for (var e : m.runs().entrySet()) runs.put(e.getKey(), e.getValue().calm());
            out.add(new NodeSpec(m.name(), m.pool(), m.instance(), m.zone(), runs,
                    List.of(), m.memoryCapMb(), m.diskCapMb(), m.where()));
        }
        return new Scenario(file, seed, scale, units, input, out,
                net, retries, simulatedDuration, mode);
    }

    /**
     * The same cluster, resized.
     *
     * <p>Every pool that had more than one machine is regenerated at {@code n},
     * keeping its instance type, its zones and its services. Singletons — the
     * coordinator, usually — are left alone: varying the data and the cluster
     * independently is what lets a resource be attributed to the right one, and
     * that only works if resizing means resizing the workers.
     */
    public Scenario withWorkers(int n) {
        var out = new java.util.ArrayList<NodeSpec>();
        var seen = new java.util.LinkedHashSet<String>();
        for (NodeSpec m : nodes) {
            if (!seen.add(m.pool())) continue;
            var pool = nodes.stream().filter(x -> x.pool().equals(m.pool())).toList();
            if (pool.size() == 1) { out.add(m); continue; }
            String prefix = m.name().replaceAll("\\d+$", "");
            var zones = pool.stream().map(NodeSpec::zone).distinct().toList();
            for (int i = 0; i < n; i++)
                // Failures cycle with the pool the way zones do. A pool writes one
                // list that every node in it draws its own afternoon from, and
                // `overrides:` is how one node differs — so a resize that copied
                // node 0's list to all n would silently delete the override, and
                // the cell that exists to measure a kill would measure a clean run
                // under the name of a weathered one.
                out.add(new NodeSpec(prefix + i, m.pool(), m.instance(),
                        zones.get(i % zones.size()), m.runs(),
                        pool.get(i % pool.size()).failures(),
                        m.memoryCapMb(), m.diskCapMb(), m.where()));
        }
        // A partition names the other end by name, and the resize may have removed
        // it. Every other failure is aimed at the node it is written in, so it
        // survives the resize the way the node does — which is the point of
        // nesting them there.
        var kept = out.stream().map(NodeSpec::name).toList();
        out.replaceAll(m -> {
            var live = m.failures().stream()
                    .filter(f -> f.other() == null || kept.contains(f.other())).toList();
            return live.size() == m.failures().size() ? m
                    : new NodeSpec(m.name(), m.pool(), m.instance(), m.zone(), m.runs(),
                            live, m.memoryCapMb(), m.diskCapMb(), m.where());
        });
        return new Scenario(file, seed, scale, units, input, out,
                net, retries, simulatedDuration, mode);
    }

    /** The same cluster with caps the engine solved for, per machine, per resource. */
    public Scenario withCaps(java.util.Map<String, double[]> byMachine) {
        var out = new java.util.ArrayList<NodeSpec>();
        for (NodeSpec m : nodes) {
            double[] caps = byMachine.get(m.name());
            out.add(caps == null ? m : new NodeSpec(m.name(), m.pool(), m.instance(),
                    m.zone(), m.runs(), m.failures(), caps[0], caps[1], m.where()));
        }
        return new Scenario(file, seed, scale, units, input, out,
                net, retries, simulatedDuration, mode);
    }

    /**
     * One node, as the simulation declared it.
     *
     * <p>A null cap means "whatever the instance type says". Scaled mode fills
     * them in instead, per resource, from what the engine solved for.
     *
     * @param runs     what this node serves, keyed by the service's name in the
     *                 {@code .proto} and valued by the {@code .java} file that
     *                 implements it. Both, in one key, because a scenario that
     *                 named only the class produced a trace naming only the
     *                 service, under the same heading, and nothing in the file
     *                 said they were the same thing.
     * @param failures what happens to this node. Written inside it, so a failure
     *                 cannot name a node that is not there — there is no name to
     *                 get wrong. The one exception is a partition, which is a
     *                 property of a pair and so has to say the other end.
     */
    public record NodeSpec(String name, String pool, String instance, String zone,
                           Map<String, ServiceSpec> runs, List<Failure> failures,
                           Double memoryCapMb, Double diskCapMb, String where) {}

    /**
     * One service a node runs: what it is called on the wire, and the file that
     * implements it.
     *
     * @param service   the name in the {@code .proto}, which is what discovery uses
     * @param path      the {@code .java} file, relative to the project root
     * @param className what that file's class would be loaded under — its package
     *                  declaration and its own name, derived rather than typed a
     *                  second time
     */
    public record ServiceSpec(String service, String path, String className,
                              Map<String, List<RpcFailure>> failures, String where) {
        /** The same service with nothing wrong with it, for {@link #withoutWeather()}. */
        public ServiceSpec calm() {
            return failures.isEmpty() ? this
                    : new ServiceSpec(service, path, className, Map.of(), where);
        }
    }

    public record NetSpec(double sameZoneRefMs, double crossZoneRefMs,
                          double jitterRefMs, double loss) {
        public static NetSpec none() { return new NetSpec(0, 0, 0, 0); }
    }

    /** What can happen to a node. */
    public enum Kind {
        KILL, FREEZE, DEGRADE, SPOT_RECLAIM, PARTITION, HEAL, RESTART;

        /**
         * What this is called in the file: {@code SPOT_RECLAIM} -> {@code spotReclaim}.
         *
         * <p>Derived rather than tabulated, and here rather than in the loader,
         * because the console reads these keys back and a second table would be a
         * second spelling to keep in step.
         */
        public String key() {
            var parts = name().toLowerCase().split("_");
            var sb = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++)
                sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            return sb.toString();
        }
    }

    /**
     * One thing that happens to the node it is written in — once, or at a rate.
     *
     * <p>Exactly one of {@code atRefMs} and {@code perRefMs} is set, and the
     * loader refuses an entry that sets both or neither. The distinction is the
     * whole of what failures teach. A simulation that kills a node at 900 refMs
     * teaches the system to survive 900 refMs; a rate teaches it to survive
     * whenever, which is the harder and more honest thing — and it is why sweeps
     * exist: one seed shows a design survived an afternoon, twenty show it
     * survives afternoons.
     *
     * @param other             the far end of a partition or a heal, and null for
     *                          every other kind. Reachability is a property of a
     *                          pair, so one of the two names has to be written
     *                          down; the block supplies the other.
     * @param restartAfterRefMs if positive, the node comes back this long after it
     *                          went away — which is a different exercise from a
     *                          node that never returns
     */
    public record Failure(Kind kind, double atRefMs, double perRefMs, String other,
                          double forRefMs, double factor, double noticeRefMs,
                          double restartAfterRefMs, String where) {

        /** Whether this one is drawn from a rate rather than scripted at an instant. */
        public boolean drawn() { return perRefMs > 0; }
    }

    /** What can happen to one rpc on one node. */
    public enum RpcKind { STATUS, SLOW, DROP }

    /**
     * One thing that happens to one rpc, one call in {@code perCalls}.
     *
     * <p>Rates only, and no instant: a failure that begins at a moment and stays
     * is a property of the node, and {@code degrade} already says it. What this
     * says instead is the thing a node-level failure cannot — that a service is
     * bad <i>here</i> and fine on its peers, which is the one replica every design
     * handles worst.
     *
     * @param status the code the caller gets, for {@link RpcKind#STATUS}
     * @param factor how many times its declared duration this call takes, for
     *               {@link RpcKind#SLOW}
     */
    public record RpcFailure(RpcKind kind, io.grpc.Status.Code status, double factor,
                             int perCalls, String where) {}
}
