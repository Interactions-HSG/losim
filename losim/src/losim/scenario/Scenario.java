package losim.scenario;

import java.util.List;
import losim.runtime.Retry;

/**
 * A fleet, its weather and its bad afternoon — as data.
 *
 * <p>Nothing here is computed. Anything that needs code points at a class by name,
 * so the file stays diffable, sweepable and readable by someone who did not write
 * it. That matters more than it sounds: comparing two designs means comparing two
 * of these, and a scenario that hides a decision in a script cannot be compared at
 * all.
 *
 * <p>Every duration is reference-machine time (D3), which is why they are
 * {@code double refMs} throughout and why the file has to say so.
 */
public record Scenario(
        String file,
        long seed,
        double kTime,
        String job,
        double expectedRunRefMs,
        List<MachineSpec> machines,
        NetSpec net,
        List<Fault> faults,
        List<Chaos> chaos,
        List<Retry> retries,
        boolean tightMargin,
        Mode mode,
        Workload workload) {

    /**
     * Whether the workload fits.
     *
     * <p>There are exactly two, and no third. In {@link #DIRECT} nothing is scaled
     * and nothing is inferred: every number on screen is what happened. In
     * {@link #SCALED} the scaler engine decides the run size, the fleet, k_time and
     * every cap, and projects the results back with error bars. A scenario cannot
     * hand-declare a shrink factor and bypass the engine — that would be a third
     * mode whose numbers nobody could account for.
     */
    public enum Mode { DIRECT, SCALED }

    /**
     * How much work there is, at full scale.
     *
     * @param records     the size the design is meant to handle. In direct mode this
     *                    is simply what runs; in scaled mode it is what gets projected to.
     * @param probeSizes  the ladder the engine climbs to fit its laws. Four points is
     *                    the fewest that can show a bend.
     * @param workerCounts  how many machines to put in each multi-machine pool, varied
     *                      independently of the data so that a resource can be attributed
     *                      to the right variable rather than to whichever one happened to
     *                      move with it. Not a count of machines in the fleet: a scenario
     *                      with a coordinator and a pool of four, probed at 2, runs three
     *                      machines.
     */
    public record Workload(long records, List<Integer> probeSizes, List<Integer> workerCounts,
                           String where) {}

    /** The declared full-scale size, or one if the scenario never said. */
    public long records() { return workload == null ? 1 : workload.records(); }

    // ------------------------------------------------------------------ variants
    //
    // The probe grid needs the same scenario at many sizes, fleet shapes and seeds.
    // Producing those here rather than by editing files keeps one fact — what this
    // system is — in one place, and makes the grid's axes explicit.

    public Scenario withSeed(long seed) {
        return new Scenario(file, seed, kTime, job, expectedRunRefMs, machines, net,
                faults, chaos, retries, tightMargin, mode, workload);
    }

    public Scenario withKTime(double k) {
        return new Scenario(file, seed, k, job, expectedRunRefMs, machines, net,
                faults, chaos, retries, tightMargin, mode, workload);
    }

    public Scenario withRecords(long n) {
        var w = workload == null
                ? new Workload(n, List.of(1000, 2000, 4000, 8000), List.of(2, 4), file)
                : new Workload(n, workload.probeSizes(), workload.workerCounts(), workload.where());
        return new Scenario(file, seed, kTime, job, expectedRunRefMs, machines, net,
                faults, chaos, retries, tightMargin, mode, w);
    }

    public Scenario withMode(Mode m) {
        return new Scenario(file, seed, kTime, job, expectedRunRefMs, machines, net,
                faults, chaos, retries, tightMargin, m, workload);
    }

    /** The same scenario with no weather at all — the clean column of the grid. */
    public Scenario withoutWeather() {
        return new Scenario(file, seed, kTime, job, expectedRunRefMs, machines, net,
                List.of(), List.of(), retries, tightMargin, mode, workload);
    }

    public Scenario withExpectedRun(double refMs) {
        return new Scenario(file, seed, kTime, job, refMs, machines, net,
                faults, chaos, retries, tightMargin, mode, workload);
    }

    /**
     * The same fleet, resized.
     *
     * <p>Every pool that had more than one machine is regenerated at {@code n},
     * keeping its instance type, its zones and its services. Singletons — the
     * coordinator, usually — are left alone: varying the data and the fleet
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
        return new Scenario(file, seed, kTime, job, expectedRunRefMs, out, net,
                stillThere, chaos, retries, tightMargin, mode, workload);
    }

    /** The same fleet with caps the engine solved for, per machine, per resource. */
    public Scenario withCaps(java.util.Map<String, double[]> byMachine) {
        var out = new java.util.ArrayList<MachineSpec>();
        for (MachineSpec m : machines) {
            double[] caps = byMachine.get(m.name());
            out.add(caps == null ? m : new MachineSpec(m.name(), m.pool(), m.instance(),
                    m.zone(), m.runs(), caps[0], caps[1], m.where()));
        }
        return new Scenario(file, seed, kTime, job, expectedRunRefMs, out, net,
                faults, chaos, retries, tightMargin, mode, workload);
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
     * <p>A scenario with a fault at 900 refMs teaches the fleet to survive 900 refMs.
     * A rate teaches it to survive whenever, which is the harder and more honest
     * thing — and it is why sweeps exist: one seed shows a design survived an
     * afternoon, twenty show it survives afternoons.
     */
    public record Chaos(Kind kind, double everyRefMs, String among,
                        double factor, double forRefMs, String where) {}
}
