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
        String job,
        /**
         * Where {@code job:} was written, for the refusals that are about the class
         * rather than the line — that it is not {@link losim.api.Scalable} when the
         * scenario asks for a model, that its input has a part this file never
         * sized. The loader never loads a class, so those questions are asked once
         * the run has one, and they still have to read like every other refusal in
         * the file.
         */
        String jobWhere,
        double scale,
        long units,
        /**
         * How big each part of the input is, in the order the file names them.
         *
         * <p>The job says what its input is made of; this says how much. Checked
         * against the job's own {@code shape()} at load, so a part named here that
         * the job does not consume is refused with its line rather than ignored.
         */
        List<InputSize> input,
        List<MachineSpec> machines,
        NetSpec net,
        List<Fault> faults,
        List<Chaos> chaos,
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
        Map<String, Cost> takes,
        boolean tightMargin,
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
     * run is whatever the job does with the one record it is given. That is what a
     * scenario saying nothing about scale has always got, and it stays that way.
     *
     * <p>Above 1 the scenario is a model of something bigger, and then the size is
     * {@code scale} times {@link #BASE} — the biggest run the engine can actually
     * measure. The step between the two is a step between kinds, not a slider: a
     * run and a model of a run are different claims, and only the second one has
     * error bars.
     */
    public long fullUnits() { return scale <= 1 ? 1 : Math.round(scale * BASE); }

    /**
     * How many machines to put in each multi-machine pool, varied on its own.
     *
     * <p>Derived from the cluster that was drawn, because that is the cluster the
     * question is about: probing a design at two workers when it is written for
     * eight extrapolates across the very thing being measured. Varied independently
     * of the data so a resource can be attributed to the right variable rather than
     * to whichever one happened to move with it.
     */
    public List<Integer> workerCounts() {
        int declared = (int) machines.stream().filter(m -> !m.runs().isEmpty()).count();
        return List.of(Math.max(2, declared / 2), Math.max(3, declared));
    }

    // ------------------------------------------------------------------ variants
    //
    // The probe grid needs the same scenario at many sizes, cluster shapes and seeds.
    // Producing those here rather than by editing files keeps one fact — what this
    // system is — in one place, and makes the grid's axes explicit.

    public Scenario withSeed(long seed) {
        return new Scenario(file, seed, job, jobWhere, scale, units, input, machines, net,
                faults, chaos, retries, takes, tightMargin, mode);
    }

    /** The run size the engine solved for, replacing the full-scale one. */
    public Scenario withUnits(long n) {
        return new Scenario(file, seed, job, jobWhere, scale, n, input, machines, net,
                faults, chaos, retries, takes, tightMargin, mode);
    }

    public Scenario withMode(Mode m) {
        return new Scenario(file, seed, job, jobWhere, scale, units, input, machines, net,
                faults, chaos, retries, takes, tightMargin, m);
    }

    /** The same scenario with no weather at all — the clean column of the grid. */
    public Scenario withoutWeather() {
        return new Scenario(file, seed, job, jobWhere, scale, units, input, machines, net,
                List.of(), List.of(), retries, takes, tightMargin, mode);
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
        var out = new java.util.ArrayList<MachineSpec>();
        var seen = new java.util.LinkedHashSet<String>();
        for (MachineSpec m : machines) {
            if (!seen.add(m.pool())) continue;
            var pool = machines.stream().filter(x -> x.pool().equals(m.pool())).toList();
            if (pool.size() == 1) { out.add(m); continue; }
            String prefix = m.name().replaceAll("\\d+$", "");
            var zones = pool.stream().map(MachineSpec::zone).distinct().toList();
            for (int i = 0; i < n; i++)
                out.add(new MachineSpec(prefix + i, m.pool(), m.instance(),
                        zones.get(i % zones.size()), m.runs(),
                        m.memoryCapMb(), m.diskCapMb(), m.where()));
        }
        // Weather aimed at a machine the resize removed would be aimed at nothing.
        var kept = out.stream().map(MachineSpec::name).toList();
        var stillThere = faults.stream()
                .filter(f -> kept.contains(f.target()) && (f.other() == null || kept.contains(f.other())))
                .toList();
        return new Scenario(file, seed, job, jobWhere, scale, units, input, out, net,
                stillThere, chaos, retries, takes, tightMargin, mode);
    }

    /** The same cluster with caps the engine solved for, per machine, per resource. */
    public Scenario withCaps(java.util.Map<String, double[]> byMachine) {
        var out = new java.util.ArrayList<MachineSpec>();
        for (MachineSpec m : machines) {
            double[] caps = byMachine.get(m.name());
            out.add(caps == null ? m : new MachineSpec(m.name(), m.pool(), m.instance(),
                    m.zone(), m.runs(), caps[0], caps[1], m.where()));
        }
        return new Scenario(file, seed, job, jobWhere, scale, units, input, out, net,
                faults, chaos, retries, takes, tightMargin, mode);
    }

    /**
     * One machine.
     *
     * <p>A null cap means "whatever the instance type says". Scaled mode fills them
     * in instead, per resource, from what the engine solved for.
     */
    /**
     * One machine, as the scenario declared it.
     *
     * @param runs the <b>Java classes</b> this machine runs, fully qualified. Called
     *             {@code runs} and not {@code serves} because the trace's own
     *             {@code serves} is a different list — the <b>gRPC services</b> those
     *             classes turned out to offer. One word for both meant a scenario
     *             saying {@code [lab.Combiner]} produced a trace saying
     *             {@code ["Worker"]} under the same heading.
     */
    public record MachineSpec(String name, String pool, String instance, String zone,
                              List<String> runs, Double memoryCapMb, Double diskCapMb,
                              String where) {}

    public record NetSpec(double sameZoneRefMs, double crossZoneRefMs,
                          double jitterRefMs, double loss) {
        public static NetSpec none() { return new NetSpec(0, 0, 0, 0); }
    }

    /** What can be done to a machine, and when. */
    public enum Kind { KILL, FREEZE, DEGRADE, SPOT_RECLAIM, PARTITION, HEAL, RESTART }

    /**
     * One thing that happens at one instant.
     *
     * @param restartAfterRefMs if positive, the machine comes back this long after
     *                          it went away — which is a different exercise from a
     *                          machine that never returns
     */
    public record Fault(double atRefMs, Kind kind, String target, String other,
                        double forRefMs, double factor, double noticeRefMs,
                        double restartAfterRefMs, String where) {}

    /**
     * A standing chance of a bad day, rather than a scripted one.
     *
     * <p>A scenario with a fault at 900 refMs teaches the cluster to survive 900 refMs.
     * A rate teaches it to survive whenever, which is the harder and more honest
     * thing — and it is why sweeps exist: one seed shows a design survived an
     * afternoon, twenty show it survives afternoons.
     */
    public record Chaos(Kind kind, double everyRefMs, String among,
                        double factor, double forRefMs, String where) {}
}
