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
        boolean tightMargin) {

    /**
     * One machine.
     *
     * <p>A null cap means "whatever the instance type says". Scaled mode fills them
     * in instead, per resource, from what the engine solved for.
     */
    public record MachineSpec(String name, String pool, String instance, String zone,
                              List<String> serves, Double memoryCapMb, Double diskCapMb,
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
