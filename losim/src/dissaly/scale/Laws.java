package dissaly.scale;

import java.util.*;

/**
 * What each resource is a function of, how it grows, and when to say so.
 *
 * <p>Two kinds of refusal live here, and they answer different questions.
 *
 * <p><b>The ladder bends.</b> Code that spills above a threshold, or switches
 * algorithm, cannot be extrapolated across. R² will not catch it: on a workload
 * whose reducer spills to disk above a key count, R² over the whole ladder falls
 * only to about 0.74 — a value a merely noisy linear workload scores just as
 * easily. Splitting the ladder does catch it, unambiguously (see
 * {@link Fit#halvesDiverge}).
 *
 * <p><b>The law cannot be reproduced.</b> R² says how well a line went through the
 * points it was given; it says nothing about whether those points would land there
 * again. On short handlers the time exponent moves by about 0.25 between
 * independent seed sets of an identical workload, while memory does not move at
 * all. A law whose exponent cannot be reproduced to better than its own effect
 * size is not a law — it is a number with a line through it — and projecting from
 * it produces an error bar wider than the thing it is being asked to distinguish.
 */
public record Laws(Map<String, Fit.Law> byResource,
                   Map<String, Double> errorBars,
                   Map<String, String> refused,
                   Map<String, Fit.Law> byCostSite,
                   Map<String, Double> amplification,
                   Map<String, Fit.Law> byVariable) {

    /**
     * How wide an error bar a projection may carry before it is not worth making.
     *
     * <p>An uncertainty of {@code wobble} in the exponent becomes a factor of
     * {@code F^wobble} in the answer, where F is how far the projection reaches. Past
     * a factor of two the projection no longer distinguishes anything a reader would
     * have wanted distinguished.
     */
    public static final double WIDEST_USABLE_ERROR_BAR = 2.0;

    /**
     * One candidate variable's account of a resource.
     *
     * <p>Ranked by whether it would be reproduced, then by whether the ladder is
     * straight, then by how well it fits. That order is deliberate: R² is about the
     * points already taken and the question is about the next ones.
     */
    private record Candidate(Fit.Law law, double errorBar, double divergence) {

        boolean better(Candidate other) {
            boolean mine = usable(), theirs = other.usable();
            if (mine != theirs) return mine;
            if (Math.abs(errorBar - other.errorBar) > 0.01) return errorBar < other.errorBar;

            // A tie goes to units, which is known exactly at full scale rather than
            // itself projected. Anything else has to earn its place by halving the
            // variance the simpler answer leaves unexplained — otherwise a variable
            // that is merely units-plus-a-constant wins on a hair of R2 and then
            // extrapolates its own offset as though it were growth.
            boolean iAmDirect = law.variable().equals("units");
            boolean itIsDirect = other.law().variable().equals("units");
            if (iAmDirect != itIsDirect) {
                Candidate direct = iAmDirect ? this : other;
                Candidate indirect = iAmDirect ? other : this;
                boolean worthIt = (1 - indirect.law().r2()) < (1 - direct.law().r2()) / 2;
                return worthIt == !iAmDirect;
            }
            return law.r2() > other.law().r2();
        }

        boolean usable() {
            return errorBar <= WIDEST_USABLE_ERROR_BAR && divergence <= Fit.DISCONTINUITY;
        }
    }

    public boolean has(String resource) { return byResource.containsKey(resource); }

    public Fit.Law law(String resource) { return byResource.get(resource); }

    /**
     * What a candidate variable itself becomes at a given number of units.
     *
     * <p>This is the second half of the attribution and it is what makes the
     * projection hold. Peak reducer memory is a function of distinct keys; distinct
     * keys is a sublinear function of units. Fitting memory against keys and keys
     * against units composes two well-behaved laws. Fitting memory against units
     * directly gives one fragile exponent that will not survive a change of corpus.
     */
    public double variableAt(String variable, double units) {
        if (variable.equals("units")) return units;
        Fit.Law law = byVariable.get(variable);
        return law == null ? units : law.at(units);
    }

    /** The projected demand at a given number of units, or empty where the engine refused (D7). */
    public OptionalDouble project(String resource, double units) {
        Fit.Law law = byResource.get(resource);
        if (law == null) return OptionalDouble.empty();
        return OptionalDouble.of(law.at(variableAt(law.variable(), units))
                * amplification.getOrDefault(resource, 1.0));
    }

    /**
     * A cluster figure rebuilt from its machines, and who could not be included.
     *
     * <p>Empty where no machine could be projected at all. Where some could and some
     * could not, the value is what the rest come to and {@code missing} names the
     * others — because a peak that is short one machine is a lower bound and has to
     * be read as one, and a total that is short one is simply wrong.
     */
    public record Across(OptionalDouble value, List<String> missing) {}

    /**
     * What a cluster resource comes to at a given size, assembled from the per-machine
     * laws rather than from a law fitted to the aggregate.
     *
     * <p>See {@link Probe#isPeak} for why the difference is not cosmetic. This is the
     * arithmetic a reader would do by hand if handed the per-machine table, and doing
     * it here means the cluster line and the machine lines cannot disagree.
     */
    public Across across(String resource, double units, Collection<String> machines) {
        boolean peak = Probe.isPeak(resource);
        var missing = new ArrayList<String>();
        double total = 0;
        boolean any = false;
        for (String machine : machines) {
            String key = Probe.perNode(machine, resource);
            // A machine with no law at all is not the same as one that was refused,
            // but for an aggregate they amount to the same thing: it cannot be
            // counted, and pretending otherwise silently understates the answer.
            var one = project(key, units);
            if (one.isEmpty()) { missing.add(machine); continue; }
            total = any ? (peak ? Math.max(total, one.getAsDouble()) : total + one.getAsDouble())
                        : one.getAsDouble();
            any = true;
        }
        return new Across(any ? OptionalDouble.of(total) : OptionalDouble.empty(), List.copyOf(missing));
    }

    /** Every machine this run fitted or refused a per-machine law for, in name order. */
    public Set<String> machines() {
        var out = new TreeSet<String>();
        for (String key : byResource.keySet()) if (Probe.isPerNode(key)) out.add(Probe.machineOf(key));
        for (String key : refused.keySet()) if (Probe.isPerNode(key)) out.add(Probe.machineOf(key));
        return out;
    }

    /** The variable part alone — what shrinks when the workload does. Fixed overhead does not. */
    public double variablePart(String resource, double units) {
        Fit.Law law = byResource.get(resource);
        if (law == null) return 0;
        return law.coefficient() * Math.pow(variableAt(law.variable(), units), law.beta());
    }

    // --------------------------------------------------------------------- fit

    /**
     * Fits every resource against every candidate variable and keeps the best.
     *
     * @param scaleFactor how far the projection has to reach, which is what decides
     *                    whether an exponent's own wobble matters
     */
    public static Laws fit(Grid grid, double scaleFactor) {
        var byResource = new TreeMap<String, Fit.Law>();
        var errorBars = new TreeMap<String, Double>();
        var refused = new TreeMap<String, String>();

        var rungs = grid.dataLadder().stream().map(Probe::medianOf).toList();
        if (rungs.size() < 4)
            throw new IllegalArgumentException("a ladder of " + rungs.size() + " cannot show"
                    + " whether a law bends; four is the fewest that can");

        var candidates = candidateVariables(rungs);
        var resources = new TreeSet<String>();
        for (Probe p : rungs) resources.addAll(p.resources().keySet());

        // Each candidate variable as a function of units, fitted first — because a
        // resource fitted against a variable that is itself projected inherits that
        // variable's uncertainty, and the compounding has to be part of the choice
        // rather than discovered afterwards.
        double[] unitsAxis = rungs.stream()
                .mapToDouble(p -> p.variables().getOrDefault("units", 0.0)).toArray();
        var byVariable = new TreeMap<String, Fit.Law>();
        var variableWobble = new TreeMap<String, Double>();
        for (String variable : candidates) {
            if (variable.equals("units")) continue;
            double[] v = rungs.stream()
                    .mapToDouble(p -> p.variables().getOrDefault(variable, 0.0)).toArray();
            if (!varies(unitsAxis) || Arrays.stream(v).anyMatch(x -> x <= 0)) continue;
            byVariable.put(variable, Fit.withFixedTerm(variable, "units", unitsAxis, v));
            variableWobble.put(variable, Math.max(0,
                    spread(grid, "units", pr -> pr.variables().getOrDefault(variable, 0.0))));
        }

        for (String resource : resources) {
            double[] y = rungs.stream().mapToDouble(p -> p.resources().getOrDefault(resource, 0.0))
                              .toArray();
            // Nothing, everywhere, is an answer — and it is the easiest answer there
            // is. A machine that writes no disk writes none at a thousand units and
            // none at a hundred million, and the projection is zero with nothing to
            // be uncertain about. Refusing it says "I could not tell" about the one
            // case that is not in doubt, and a reader who is shown a refusal beside
            // a machine goes looking for the measurement that failed.
            //
            // Checked before the guard below, because that guard is right about what
            // it is for: a power law cannot be fitted through a zero, so a resource
            // that is zero at *some* rungs and not others still has nothing usable.
            // All zeros is a different shape from some zeros.
            if (Arrays.stream(y).allMatch(v -> v == 0)) {
                byResource.put(resource, new Fit.Law(resource, "units", 0, 0, 1, 1, 0));
                errorBars.put(resource, 1.0);
                continue;
            }
            if (Arrays.stream(y).anyMatch(v -> v <= 0)) {
                refused.put(resource, "it was measured at zero on some rungs and not others, so no"
                        + " power law passes through it — a resource that appears only above some"
                        + " size has a threshold in it, and the ladder cannot see past a threshold");
                continue;
            }
            // The discontinuity test belongs on the axis the ladder was climbed on.
            // A resource that bends against units has changed behaviour between the
            // small end and the large end, and no choice of variable makes that
            // legitimate — re-parameterising a bend only hides it, and the hidden
            // version extrapolates confidently and wrongly.
            double bendsOnLadder = Fit.halvesDiverge(unitsAxis, y);
            if (bendsOnLadder > Fit.DISCONTINUITY) {
                // The R2 is quoted because it is the number that would have said
                // nothing was wrong. A reader who reaches for it instead — and it is
                // the obvious thing to reach for — should be able to see from the
                // refusal itself why it would not have served.
                refused.put(resource, String.format(
                        "the ladder bends: over the lower half it grows as units^%.2f and over"
                        + " the upper half as units^%.2f, a difference of %.2f. Something behaves"
                        + " differently small than large — a threshold, a different code path, or a"
                        + " granularity that only shows up when the pieces are few — and no"
                        + " extrapolation across that means anything. R2 over the whole ladder is"
                        + " still %.3f, which is why no threshold on R2 could have separated this"
                        + " from a merely noisy straight line",
                        Fit.lowerBeta(unitsAxis, y), Fit.upperBeta(unitsAxis, y), bendsOnLadder,
                        Fit.power(unitsAxis, y)[1]));
                continue;
            }

            Candidate best = null;
            for (String variable : candidates) {
                double[] x = rungs.stream()
                        .mapToDouble(p -> p.variables().getOrDefault(variable, 0.0)).toArray();
                if (!varies(x)) continue;
                var law = Fit.withFixedTerm(resource, variable, x, y);
                double wobble = wobbleOf(grid, resource, variable);
                // The variable's own uncertainty rides along, raised to the
                // resource's exponent: a law built on a projected quantity cannot be
                // more certain than the quantity it is built on.
                double carried = law.beta() * variableWobble.getOrDefault(variable, 0.0);
                double bar = Math.pow(Math.max(scaleFactor, 1.0), Math.max(0, wobble) + carried);
                var c = new Candidate(new Fit.Law(resource, variable, law.fixed(),
                        law.coefficient(), law.beta(), law.r2(), wobble),
                        bar, Fit.halvesDiverge(x, y));
                // Between two laws that both fit, prefer the one that would land in
                // the same place again: R2 rewards a line through these points, and
                // the question is about the next ones.
                if (best == null || c.better(best)) best = c;
            }
            if (best == null) {
                refused.put(resource, "nothing measured varied with it, so it has no law here");
                continue;
            }
            // Order matters. An exponent that cannot be reproduced cannot establish
            // that a ladder bends either, so an unreproducible measurement is refused
            // as unreproducible rather than as a discontinuity it has no standing to
            // claim.
            if (best.errorBar() > WIDEST_USABLE_ERROR_BAR) {
                refused.put(resource, String.format(
                        "its exponent moves by %.3f between independent seed sets of the same"
                        + " workload, which over a factor of %.0f is an error bar of x%.1f —"
                        + " wider than anything it would be asked to distinguish",
                        best.law().wobble(), scaleFactor, best.errorBar()));
                continue;
            }
            byResource.put(resource, best.law());
            errorBars.put(resource, best.errorBar());
        }

        var byCostSite = fitCostSites(rungs, candidates, grid, scaleFactor, variableWobble);
        var amplification = new TreeMap<String, Double>();
        for (String resource : resources) amplification.put(resource, grid.amplification(resource));
        return new Laws(byResource, errorBars, refused, byCostSite, amplification, byVariable);
    }

    /**
     * A law per cost site, not one for the whole run.
     *
     * <p>A hash lookup is flat in n; a per-unit scan is linear; a sort is n log n;
     * a shuffle is quadratic in the cluster. One exponent for all of them would be
     * wrong for most.
     */
    private static Map<String, Fit.Law> fitCostSites(List<Probe> rungs, List<String> candidates,
                                                     Grid grid, double scaleFactor,
                                                     Map<String, Double> variableWobble) {
        var out = new TreeMap<String, Fit.Law>();
        var sites = new TreeSet<String>();
        for (Probe p : rungs) sites.addAll(p.costSites().keySet());
        for (String site : sites) {
            double[] y = rungs.stream().mapToDouble(p -> p.costSites().getOrDefault(site, 0.0))
                              .toArray();
            if (Arrays.stream(y).anyMatch(v -> v <= 0)) continue;
            Candidate best = null;
            for (String variable : candidates) {
                double[] x = rungs.stream()
                        .mapToDouble(p -> p.variables().getOrDefault(variable, 0.0)).toArray();
                if (!varies(x)) continue;
                var law = Fit.withFixedTerm(site, variable, x, y);
                double wobble = costSiteWobble(grid, site, variable);
                double carried = law.beta() * variableWobble.getOrDefault(variable, 0.0);
                var c = new Candidate(new Fit.Law(site, variable, law.fixed(), law.coefficient(),
                        law.beta(), law.r2(), wobble),
                        Math.pow(Math.max(scaleFactor, 1.0), Math.max(0, wobble) + carried),
                        Fit.halvesDiverge(x, y));
                if (best == null || c.better(best)) best = c;
            }
            // Kept only where it would survive being measured again. On handlers
            // shorter than the host's own jitter, that is usually nowhere.
            if (best != null && best.usable()) out.put(site, best.law());
        }
        return out;
    }

    // ------------------------------------------------------------------ wobble

    /**
     * How far this exponent moves when nothing has changed.
     *
     * <p>Refit on each seed independently: same workload, same sizes, different
     * random draws. The spread is the error bar, and it is measured per host and per
     * workload rather than assumed, because a two-core Codespace is noisier than a
     * laptop and the same constant would not serve both.
     */
    private static double wobbleOf(Grid grid, String resource, String variable) {
        return spread(grid, variable, p -> p.resources().getOrDefault(resource, 0.0));
    }

    private static double costSiteWobble(Grid grid, String site, String variable) {
        return spread(grid, variable, p -> p.costSites().getOrDefault(site, 0.0));
    }

    /** Seeds per independent set. Fewer than two and a set is one run, not a sample. */
    static final int SEEDS_PER_SET = 2;

    private static double spread(Grid grid, String variable,
                                 java.util.function.ToDoubleFunction<Probe> of) {
        int seeds = grid.dataLadder().get(0).size();
        int sets = seeds / SEEDS_PER_SET;
        if (sets < 2) return -1;

        // Refit the way the law itself is fitted — on the median of a set of seeds,
        // not on one run. A single-seed refit measures the noise in one run and
        // reports it as the uncertainty of a law that was never fitted that way,
        // which overstates the error bar by roughly the square root of the set size.
        var betas = new ArrayList<Double>();
        for (int set = 0; set < sets; set++) {
            var x = new double[grid.dataLadder().size()];
            var y = new double[grid.dataLadder().size()];
            boolean usable = true;
            for (int rung = 0; rung < x.length && usable; rung++) {
                var probes = grid.dataLadder().get(rung);
                var slice = new ArrayList<Probe>();
                for (int i = set * SEEDS_PER_SET; i < (set + 1) * SEEDS_PER_SET && i < probes.size(); i++)
                    slice.add(probes.get(i));
                if (slice.isEmpty()) { usable = false; break; }
                Probe p = Probe.medianOf(slice);
                x[rung] = p.variables().getOrDefault(variable, 0.0);
                y[rung] = of.applyAsDouble(p);
                if (x[rung] <= 0 || y[rung] <= 0) usable = false;
            }
            if (usable) betas.add(Fit.power(x, y)[0]);
        }
        return Fit.wobble(betas);
    }

    // -------------------------------------------------------------- candidates

    /** Every quantity that both varied and could plausibly drive something. */
    private static List<String> candidateVariables(List<Probe> rungs) {
        var names = new TreeSet<String>();
        for (Probe p : rungs) names.addAll(p.variables().keySet());
        var out = new ArrayList<String>();
        for (String name : names) {
            double[] x = rungs.stream().mapToDouble(p -> p.variables().getOrDefault(name, 0.0))
                              .toArray();
            if (varies(x)) out.add(name);
        }
        return out;
    }

    private static boolean varies(double[] x) {
        double lo = Arrays.stream(x).min().orElse(0), hi = Arrays.stream(x).max().orElse(0);
        return lo > 0 && hi > lo * 1.05;
    }

    /**
     * A one-screen account of what was fitted and what was refused.
     *
     * <p>The cluster's laws only. There is one per machine per resource as well,
     * and printing two dozen more lines here would bury the five a reader came for
     * — so they are counted instead, and the count says how many were refused,
     * because "twenty fitted, four refused" is the thing that should send somebody
     * looking. The refusals themselves travel in the trace, one per machine, where
     * the viewer draws them beside the node they are about.
     */
    public String describe() {
        var sb = new StringBuilder();
        byResource.forEach((resource, law) -> {
            if (Probe.isPerNode(resource)) return;
            // A peak or a total is reported from the per-machine laws rather than from
            // this one, so printing this one beside that number would be showing a
            // reader the arithmetic that did not happen. It is still fitted, and still
            // in the trace, because it is what the ladder saw before aggregation.
            boolean assembled = (Probe.isPeak(resource) || Probe.isSum(resource)) && !machines().isEmpty();
            sb.append(String.format("  %-14s %s  +-x%.2f%s%s%n",
                    resource, law, errorBars.getOrDefault(resource, 1.0),
                    amplification.getOrDefault(resource, 1.0) > 1.001
                            ? String.format("  x%.2f under fault", amplification.get(resource)) : "",
                    assembled ? "   [not projected from: the cluster figure is assembled per machine]" : ""));
        });
        refused.forEach((resource, why) -> {
            if (Probe.isPerNode(resource)) return;
            sb.append(String.format("  %-14s REFUSED: %s%n", resource, why));
        });

        long fitted = byResource.keySet().stream().filter(Probe::isPerNode).count();
        long declined = refused.keySet().stream().filter(Probe::isPerNode).count();
        if (fitted + declined > 0)
            sb.append(String.format("  %-14s %d fitted, %d refused — in the trace, per machine%n",
                    "per machine", fitted, declined));
        return sb.toString();
    }
}
