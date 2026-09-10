package dissaly.scale;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import dissaly.runtime.Simulate;

/**
 * Things that must be true of a scaled run, checked after it is over.
 *
 * <p>Every check here was written because something it would have caught got past
 * everything else first. That is the only qualification a check in this file has:
 * not that it is a property worth having, but that its absence has already cost
 * somebody an afternoon.
 *
 * <ul>
 *   <li>A cluster peak fitted before aggregation understated memory by a factor of
 *       992, at R² 1.0000 and an error bar of one. Nothing looked wrong, because
 *       the fit was perfect — of the wrong series.</li>
 *   <li>A master holding an eleven-megabyte catalogue in a local variable measured
 *       as holding two hundred bytes, and the solver then sized it for a workload
 *       it did not have.</li>
 *   <li>A law that projected zero because it was fitted against numbers rounded
 *       past the point where they existed.</li>
 * </ul>
 *
 * <p><b>None of these stop a run.</b> A violation is a note with an address, in the
 * same spirit as {@code Trust}: the number is still produced, and it is produced
 * beside a sentence saying why it may not mean what it appears to. A simulator
 * that refused to finish because one of its own consistency checks failed would
 * teach a reader to stop running it.
 *
 * <p><b>And none of them are about whether a projection is right.</b> Nothing here
 * can know that. They are about whether the engine's own answers agree with each
 * other — a cluster line against its machine lines, a cap against the demand it
 * was solved for, a law against the measurement it was fitted to. Two numbers that
 * disagree mean one of them is wrong, which is a smaller claim than knowing which,
 * and a much easier one to check.
 */
public final class Invariants {

    private Invariants() {}

    /**
     * One thing that should have held and did not.
     *
     * @param name    a short handle, stable enough to grep a log for
     * @param about   the machine or resource it concerns, so a reader knows where to look
     * @param said    what is wrong, in a sentence, including both numbers
     */
    public record Violation(String name, String about, String said) {
        public Map<String, Object> asMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("invariant", name);
            m.put("about", about);
            m.put("said", said);
            return m;
        }
    }

    /**
     * How far two numbers that should be equal are allowed to be apart.
     *
     * <p>Generous, deliberately. These compare quantities that reached the same
     * place by different arithmetic — a sum of projections against a projection of
     * a sum — and floating point, rounding to three decimals for the trace, and a
     * fitted exponent all move the last digits. A check that fires on noise is a
     * check somebody turns off.
     */
    static final double TOLERANCE = 0.02;

    /**
     * A machine that allocates this much more than it retains is either streaming,
     * or holding its working set somewhere the walk cannot see.
     *
     * <p>The walk starts at a machine's services and follows their fields, so
     * anything a handler keeps in a local is invisible. That is a documented
     * boundary and {@code alsoHolds} is the way through it, but nothing until now
     * noticed when a design had walked into it. The master that prompted this
     * allocated 12.19 MB and retained 0.000191 MB — a ratio of sixty thousand.
     */
    static final double SUSPICIOUS_ALLOC_TO_RETAINED = 1000.0;

    /** Below this a retained figure is too small to draw a conclusion from either way. */
    static final double RETAINED_FLOOR_MB = 0.5;

    /**
     * @param reported what the run is actually going to print and write, so the checks
     *                 are about the numbers a reader will see rather than about numbers
     *                 recomputed here. Recomputing them makes a check that agrees with
     *                 itself by construction and can never fail — which is the same
     *                 amount of use as one that fails on every run.
     */
    public static List<Violation> of(ScalePlan plan, Probe probe, Simulate.Result result,
                                     List<ScalePlan.Projection> reported) {
        var out = new ArrayList<Violation>();
        if (!plan.feasible()) return out;

        // Counted once and handed down, because two checks need it and one of them
        // says something different depending on the answer.
        int lost = 0;
        for (var t : result.machines().values()) if (!t.alive()) lost++;
        int all = result.machines().size();
        boolean clusterGone = all > 0 && lost * 2 >= all;

        // First, because it changes what the others are allowed to say. Where the
        // cluster is gone, every check below is looking at the same one fact from a
        // different angle, and fifteen restatements of it are how a reader learns to
        // skip this section.
        theRunItselfHappened(result, lost, all, clusterGone, out);
        hiddenWorkingSet(result, out);
        refusalsStayRefused(plan, reported, out);
        aggregatesAreAssembled(plan, reported, out);
        lawsFitTheirOwnMeasurement(plan, probe, clusterGone, out);
        capsAreConsistent(plan, out);
        everyMachineIsAccountedFor(plan, result, out);
        projectionsAreSane(plan, out);
        return out;
    }

    // ------------------------------------------------------------------ the checks

    /**
     * A resource the engine refused must not come back carrying a number.
     *
     * <p>The narrowest check in this file and the one that catches the worst thing
     * that can happen. A wrong number is a bug; a refusal quietly replaced by a
     * number is a lie, because the reason a reader would have used to distrust it
     * has been deleted along the way. It shipped: a memory law refused for bending
     * was overwritten by an assembly of its per-machine parts and reported as
     * {@code projected: 0}, reason cleared, error bar 1.
     *
     * <p>Mechanical, deliberately. It compares two things the engine already knows
     * about itself — what {@code Laws} refused, and what is about to be written —
     * and needs no judgement about whether either is right.
     */
    private static void refusalsStayRefused(ScalePlan plan, List<ScalePlan.Projection> reported,
                                            List<Violation> out) {
        for (var p : reported) {
            String why = plan.laws().refused().get(p.resource());
            if (why == null || p.projected().isEmpty()) continue;
            out.add(new Violation("refusal-overwritten", p.resource(), String.format(Locale.ROOT, 
                    "was refused, and is being reported as %.4g anyway. The refusal said: %s."
                    + " A number that replaces a refusal takes the reader's grounds for"
                    + " doubting it away at the same time",
                    p.projected().getAsDouble(), why)));
        }
    }

    /**
     * A machine whose retained heap is implausibly small for what it allocated.
     *
     * <p>The one that produces a confident zero rather than a refusal, which is the
     * worse failure: a refusal invites a second look and a zero does not.
     */
    private static void hiddenWorkingSet(Simulate.Result result, List<Violation> out) {
        for (var t : result.machines().values()) {
            double allocated = t.allocatedBytes() / 1048576.0;
            double retained = t.peakRetainedBytes() / 1048576.0;
            // A machine holding a real amount is not hiding anything, whatever it
            // churned through to get there.
            if (retained >= RETAINED_FLOOR_MB) continue;
            // And one that allocated nothing either has nothing to hide. The
            // comparison is the *ratio* — this once read `allocated < FLOOR * RATIO`,
            // an absolute half-gigabyte gate that the machine which prompted the whole
            // check could never reach, so it passed on every run including the ones it
            // was written for.
            if (allocated <= 0) continue;
            double ratio = allocated / Math.max(retained, 1e-9);
            if (ratio < SUSPICIOUS_ALLOC_TO_RETAINED) continue;
            out.add(new Violation("hidden-working-set", t.name(), String.format(Locale.ROOT, 
                    "allocated %.2f MB and retained %.4f MB. Either it holds nothing across a"
                    + " call, or what it holds lives in a local variable where the heap walk"
                    + " cannot reach it — the walk starts at a machine's services and follows"
                    + " their fields. If it is the second, its memory law is fitted on nothing"
                    + " and the scale solver will size it for a workload it does not have."
                    + " Dissaly.current().alsoHolds(x) is how a handler says what it is keeping"
                    + " (ratio %,.0fx)",
                    allocated, retained, ratio)));
        }
    }

    /**
     * That the cluster figure being reported is the one assembled from the machines.
     *
     * <p>This is a regression guard, not a discovery. Fitting a law to a
     * pre-aggregated peak understated memory by a factor of 992 with a perfect R²,
     * and the fix was to combine the per-machine projections instead. So the thing
     * worth checking is that the reported number still comes from that assembly — a
     * check on the wiring, which can break, rather than on the arithmetic, which
     * agrees with itself by construction.
     *
     * <p>It deliberately does <b>not</b> compare the assembly against the old
     * pre-aggregation fit. Those two disagree on purpose and always will, so a check
     * on it would fire on every run forever and teach a reader to skim past this
     * whole section. Where that disagreement is large it is worth knowing — a peak
     * that crosses over outside the measured range is a real fact about the design —
     * but it is a note about the system, not a fault in the engine.
     */
    private static void aggregatesAreAssembled(ScalePlan plan, List<ScalePlan.Projection> reported,
                                               List<Violation> out) {
        var machines = plan.laws().machines();
        if (machines.isEmpty()) return;
        for (var p : reported) {
            String resource = p.resource();
            if (!Probe.isPeak(resource) && !Probe.isSum(resource)) continue;
            if (p.projected().isEmpty()) continue;
            var parts = plan.laws().across(resource, plan.fullUnits(), machines);
            if (parts.value().isEmpty() || !parts.missing().isEmpty()) continue;

            double said = p.projected().getAsDouble(), assembled = parts.value().getAsDouble();
            double bigger = Math.max(Math.abs(said), Math.abs(assembled));
            if (bigger <= 0 || Math.abs(said - assembled) / bigger <= TOLERANCE) continue;
            out.add(new Violation("aggregate-not-assembled", resource, String.format(Locale.ROOT, 
                    "the cluster line reports %.4g while the per-machine laws combine to %.4g."
                    + " %s has to be taken after projecting, not before: fitting it to the"
                    + " pre-aggregated series follows whichever machine is largest at probe"
                    + " size past the point another overtakes it, which once understated"
                    + " memory by a factor of 992 at an R2 of 1.0000",
                    said, assembled, Probe.isPeak(resource) ? "A peak" : "A total")));
        }
    }

    /**
     * A law against the measurement it was fitted to.
     *
     * <p>Cheap and surprisingly sharp: a law that cannot reproduce its own probe at
     * the size the probe ran is not a law about that system at all, whatever its R²
     * says. R² is about the points as a set; this is about one point that is known.
     */
    private static void lawsFitTheirOwnMeasurement(ScalePlan plan, Probe probe, boolean clusterGone,
                                                   List<Violation> out) {
        var missed = new ArrayList<String>();
        for (var e : probe.resources().entrySet()) {
            var law = plan.laws().law(e.getKey());
            if (law == null) continue;
            var at = plan.laws().project(e.getKey(), plan.units());
            if (at.isEmpty()) continue;
            double observed = e.getValue(), said = at.getAsDouble();
            if (observed <= 0) continue;
            // Wide, because a law is fitted across four rungs and is not required to
            // pass through any one of them. An order of magnitude out is not a fit.
            if (said >= observed / 3 && said <= observed * 3) continue;
            missed.add(e.getKey());
            if (clusterGone) continue;
            out.add(new Violation("law-misses-its-own-probe", e.getKey(), String.format(Locale.ROOT,
                    "at the size that was actually run (%,d units) the fitted law says %.4g"
                    + " and the run measured %.4g. A law that cannot reproduce the one point"
                    + " it is known to have been fitted at will not do better further out",
                    plan.units(), said, observed)));
        }
        // Which of the two is the anomaly is not obvious, and this decided it in the
        // law's disfavour every time. On a run where the cluster died that is exactly
        // backwards — the law is fitted across the whole ladder and the measurement is
        // one rung of it, taken on a system that was mostly gone — and it sends a
        // reader to look at the arithmetic instead of at the corpses.
        //
        // Said once, for all of them. Fifteen restatements of one fact, each three
        // lines long, is how a reader learns to skip this section entirely.
        if (clusterGone && !missed.isEmpty()) {
            out.add(new Violation("law-misses-its-own-probe", missed.size() + " of "
                    + probe.resources().size() + " resources", String.format(Locale.ROOT,
                    "are more than 3x from what the run measured at %,d units: %s."
                    + " On this run the measurement is the more likely anomaly of the two,"
                    + " for the reason cluster-did-not-survive gives — so the thing to look"
                    + " at is what stopped the machines, not the arithmetic of the fit",
                    plan.units(), String.join(", ", missed))));
        }
    }

    /**
     * A run that says it finished, out of a cluster that did not.
     *
     * <p>{@code completed} means the entry handler returned. That is a true thing to
     * record and a misleading thing to read alone: a job whose master returns after
     * every worker it was talking to has died has completed in exactly the sense
     * that nothing threw, and in no other. It shipped saying so — four workers dead,
     * a hundred and thirty-nine calls of a hundred and sixty-four timed out, and
     * {@code completed: true} at the top of the trace.
     *
     * <p>What makes it this file's business rather than the summary's is what happens
     * next: every law in the plan is fitted from what the survivors managed, and a
     * projection from that describes a smaller system than the one that was declared,
     * doing less than it was asked. Nothing else says so, because each individual
     * number is correctly measured.
     */
    private static void theRunItselfHappened(Simulate.Result result, int lost, int all,
                                             boolean clusterGone, List<Violation> out) {
        // Losing a machine is a thing simulations are for, and most of the runs that
        // do it are demonstrating something on purpose. What is worth a word is a
        // cluster that mostly stopped existing.
        if (!clusterGone) return;
        long handled = 0;
        for (var t : result.machines().values()) handled += t.handledCalls();
        out.add(new Violation("cluster-did-not-survive", lost + " of " + all + " machines",
                String.format(Locale.ROOT,
                        "died before the run ended%s. Between them the machines handled %,d"
                        + " calls. Every law in this plan is fitted from that, so what is being"
                        + " projected is what the survivors managed rather than what the design"
                        + " costs",
                        result.completed()
                                ? ", and the run reports itself completed — which it is, in the"
                                  + " sense that the entry handler returned and nothing threw,"
                                  + " and in no other"
                                : ", and the run did not finish either",
                        handled)));
    }

    /**
     * Both readings of a machine's caps, and the ratio between demand and capacity.
     *
     * <p>The ratio is the whole claim of a scale model: the probe is meant to be
     * under the same pressure the full-size system would be. Where the two disagree,
     * the machine was either given the wrong cap or is being projected by a law that
     * the cap was not solved from — which is what happens when a per-machine law and
     * the cluster law it was sized from choose different variables.
     */
    private static void capsAreConsistent(ScalePlan plan, List<Violation> out) {
        for (var e : plan.caps().entrySet()) {
            String machine = e.getKey();
            double[] probeCap = e.getValue();
            double[] fullCap = plan.fullCaps().get(machine);
            if (fullCap == null) continue;

            for (int i = 0; i < 2; i++) {
                if (probeCap[i] > fullCap[i] * (1 + TOLERANCE)) {
                    out.add(new Violation("probe-cap-above-full-cap", machine, String.format(Locale.ROOT, 
                            "was given %.1f MB of %s for the probe and would really have %.1f MB."
                            + " A scale model that hands a machine more than it has is not under"
                            + " the pressure it is modelling",
                            probeCap[i], i == 0 ? "memory" : "disk", fullCap[i])));
                }
            }
        }
    }

    /** Every machine that ran should have a law, or a reason, for every fitted resource. */
    private static void everyMachineIsAccountedFor(ScalePlan plan, Simulate.Result result,
                                                   List<Violation> out) {
        var known = plan.laws().machines();
        if (known.isEmpty()) return;
        for (String machine : result.machines().keySet()) {
            if (known.contains(machine)) continue;
            out.add(new Violation("machine-not-projected", machine, String.format(Locale.ROOT, 
                    "ran, and carries no per-machine law and no per-machine refusal. A view"
                    + " showing only projected figures has nothing to show for it, and will"
                    + " either leave it out of a cluster total or fall back to what the probe"
                    + " measured — and the probe's number is the one thing that must not be"
                    + " shown for a scaled run")));
        }
    }

    /** Nothing projected should be negative, infinite, or smaller than it was at probe size. */
    private static void projectionsAreSane(ScalePlan plan, List<Violation> out) {
        for (var e : plan.laws().byResource().entrySet()) {
            String resource = e.getKey();
            var small = plan.laws().project(resource, plan.units());
            var large = plan.laws().project(resource, plan.fullUnits());
            if (small.isEmpty() || large.isEmpty()) continue;
            double a = small.getAsDouble(), b = large.getAsDouble();

            if (!Double.isFinite(b) || b < 0) {
                out.add(new Violation("projection-not-a-number", resource, String.format(Locale.ROOT, 
                        "projects %s at full size, which is not a quantity anything consumes", b)));
                continue;
            }
            // Growth is not required — a resource may genuinely be flat — but shrinking
            // is a claim that a bigger workload costs less, and it is worth saying out
            // loud rather than implying.
            //
            // This was written as `beta >= 0 && falls`, which is a check on whether a
            // law contradicts its own exponent. That is a real thing to check and it is
            // not the interesting one: where the exponent is itself negative the law is
            // perfectly self-consistent and the claim it is making — a hundred million
            // frames finishing sooner than eight thousand — is the absurd part. The
            // precondition excluded the only case anybody would want caught. Every
            // guard written as "unless the author thought this was impossible" has that
            // shape available to it.
            if (b >= a * (1 - TOLERANCE)) continue;
            double beta = e.getValue().beta();
            out.add(new Violation("projection-shrinks", resource, String.format(Locale.ROOT,
                    "falls from %.4g at %,d units to %.4g at %,d. %s",
                    a, plan.units(), b, plan.fullUnits(),
                    beta >= 0
                        ? String.format(Locale.ROOT,
                                "Its exponent is %.3f, and a law that is not decreasing cannot"
                                + " decrease", beta)
                        : String.format(Locale.ROOT,
                                "Its exponent is %.3f, so the law is consistent and the claim is"
                                + " the problem: this says the design costs less the more of it"
                                + " there is. A fitted exponent goes negative when the quantity"
                                + " it was fitted to does not reproduce between runs, so the"
                                + " thing to check is whether the measurement is stable, not"
                                + " what the curve does past the ladder", beta))));
        }
    }
}
