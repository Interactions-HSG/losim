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
                   Map<String, Fit.Law> byVariable,
                   Map<String, String> assumed) {

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

    /**
     * The figure this run will actually report for a resource, and how it got there.
     *
     * <p>There are two answers to "what does this cluster hold at full size" — the
     * cluster law evaluated there, and the per-machine laws combined there — and they
     * are not close. For memory they were once apart by a factor of 992. So the
     * question of which one is <i>the</i> answer has to be settled in one place, or
     * every caller settles it again and they drift.
     *
     * <p>They did drift. The projection table assembled peaks from the machines while
     * the note printed underneath it evaluated the cluster law, and the trace ended up
     * saying a resource came to 16.699 directly above a sentence explaining that it
     * was 11.444 at both sizes and barely moved. Both numbers were correctly computed
     * and one of them was about a quantity nobody was being shown.
     */
    public record Reported(OptionalDouble value, boolean assembled) {}

    /** @see Reported */
    public Reported reported(String resource, double units) {
        if (!Probe.isPerNode(resource) && (Probe.isPeak(resource) || Probe.isSum(resource))) {
            var machines = machines();
            if (!machines.isEmpty()) {
                var across = across(resource, units, machines);
                // Short a machine, this is a lower bound rather than the answer, and
                // the cluster law is the better of two imperfect things to report.
                if (across.value().isPresent() && across.missing().isEmpty())
                    return new Reported(across.value(), true);
            }
        }
        return new Reported(project(resource, units), false);
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
        var assumed = new TreeMap<String, String>();

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

            // Which rung the law is fitted from, and what had to be assumed to fit
            // one at all. Everything below sets these two rather than giving up:
            // an assumption a reader can see and disagree with beats an absence
            // they can do nothing with, and the cases that used to be refused here
            // are all cases where something true can still be said as long as the
            // saying of it comes with the condition attached.
            int from = 0;
            String assumption = null;

            // Nothing, everywhere, is an answer, and the easiest one there is.
            if (Arrays.stream(y).allMatch(v -> v == 0)) {
                byResource.put(resource, new Fit.Law(resource, "units", 0, 0, 1, 1, 0));
                errorBars.put(resource, 1.0);
                continue;
            }

            // Zero on some rungs and not others is a threshold: the resource
            // switches on somewhere the ladder cannot see, because it switches on
            // between two rungs and the ladder only has the rungs. What can be said
            // is what happens above it, and where "above" begins.
            if (Arrays.stream(y).anyMatch(v -> v <= 0)) {
                int first = 0;
                while (first < y.length && y[first] <= 0) first++;
                boolean clean = first < y.length;
                for (int i = first; i < y.length && clean; i++) if (y[i] <= 0) clean = false;
                // Zeros scattered through the ladder are not a threshold, they are a
                // measurement that keeps missing. Two points is the fewest that can
                // carry an exponent at all.
                if (!clean || y.length - first < 2) {
                    refused.put(resource, "it was measured at zero on rungs above and below others,"
                            + " which is not a threshold but a measurement that keeps coming back"
                            + " empty, and nothing can be fitted through it");
                    continue;
                }
                from = first;
                assumption = String.format(Locale.ROOT, 
                        "assumed to be zero at or below %,.0f units: it was measured at zero there"
                        + " and above zero on the %d rung(s) beyond, so it switches on somewhere"
                        + " between two rungs and the law describes only the part above",
                        unitsAxis[first - 1], y.length - first);
            }

            // A bend means the small end and the large end are different regimes.
            // The projection is heading *upward*, so the upper regime is the one it
            // is heading into, and fitting it is a far better answer than discarding
            // both halves — provided the choice is stated, because a reader whose
            // real interest is the lower regime has to be able to see that it was
            // the half thrown away.
            double bendsOnLadder = from == 0 ? Fit.halvesDiverge(unitsAxis, y) : 0;
            if (bendsOnLadder > Fit.DISCONTINUITY) {
                from = Math.max(0, y.length / 2 - 1);
                assumption = String.format(Locale.ROOT, 
                        "the ladder bends: over the lower half it grows as units^%.2f and over the"
                        + " upper half as units^%.2f, a difference of %.2f. Assumed the upper"
                        + " regime holds at scale, and fitted from %,.0f units up — a projection"
                        + " climbs away from the small end, so the small end is the half worth"
                        + " dropping. R2 over the whole ladder is still %.3f, which is why no"
                        + " threshold on R2 could have found this",
                        Fit.lowerBeta(unitsAxis, y), Fit.upperBeta(unitsAxis, y), bendsOnLadder,
                        unitsAxis[from], Fit.power(unitsAxis, y)[1]);
            }

            final int at = from;
            double[] yFit = Arrays.copyOfRange(y, at, y.length);

            Candidate best = null;
            for (String variable : candidates) {
                double[] x = rungs.stream()
                        .mapToDouble(p -> p.variables().getOrDefault(variable, 0.0)).toArray();
                double[] xFit = Arrays.copyOfRange(x, at, x.length);
                if (!varies(xFit)) continue;
                var law = Fit.withFixedTerm(resource, variable, xFit, yFit);
                double wobble = wobbleOf(grid, resource, variable);
                double carried = law.beta() * variableWobble.getOrDefault(variable, 0.0);
                double bar = Math.pow(Math.max(scaleFactor, 1.0), Math.max(0, wobble) + carried);
                var c = new Candidate(new Fit.Law(resource, variable, law.fixed(),
                        law.coefficient(), law.beta(), law.r2(), wobble),
                        bar, Fit.halvesDiverge(xFit, yFit));
                if (best == null || c.better(best)) best = c;
            }

            // Nothing varied, so the only shape left is a level one. That is a law:
            // it says the resource does not follow the workload, which is a fact
            // about the design and often the most useful one on the page.
            if (best == null) {
                var seen = new ArrayList<Double>();
                for (double v : yFit) seen.add(v);
                byResource.put(resource, new Fit.Law(resource, "units", Probe.median(seen), 0, 1, 1, 0));
                errorBars.put(resource, 1.0);
                assumed.put(resource, join(assumption, "assumed constant: nothing measured varied"
                        + " with it across the ladder, so it is reported at the level it held"));
                continue;
            }

            // An exponent that moves between seed sets makes the band wide. The band
            // is the thing to say, not a reason to say nothing: the fitted value is
            // the geometric centre of a multiplicative band, so it is already the
            // middle of the range rather than a corner of it.
            if (best.errorBar() > WIDEST_USABLE_ERROR_BAR) {
                assumption = join(assumption, String.format(Locale.ROOT, 
                        "its exponent moves by %.3f between independent seed sets of the same"
                        + " workload, which over a factor of %.0f is a band of x%.1f. The value is"
                        + " the centre of that band and should be read as its order rather than"
                        + " its digits",
                        best.law().wobble(), scaleFactor, best.errorBar()));
            }

            byResource.put(resource, best.law());
            errorBars.put(resource, best.errorBar());
            if (assumption != null) assumed.put(resource, assumption);
        }

        var byCostSite = fitCostSites(rungs, candidates, grid, scaleFactor, variableWobble);
        var amplification = new TreeMap<String, Double>();
        for (String resource : resources) amplification.put(resource, grid.amplification(resource));
        return new Laws(byResource, errorBars, refused, byCostSite, amplification, byVariable,
                assumed);
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

    /** Two conditions on one number, in the order they were discovered. */
    private static String join(String first, String second) {
        return first == null ? second : first + ". Also: " + second;
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
            sb.append(String.format(Locale.ROOT, "  %-14s %s  +-x%.2f%s%s%n",
                    resource, law, errorBars.getOrDefault(resource, 1.0),
                    amplification.getOrDefault(resource, 1.0) > 1.001
                            ? String.format(Locale.ROOT, "  x%.2f under fault", amplification.get(resource)) : "",
                    assembled ? "   [not projected from: the cluster figure is assembled per machine]" : ""));
        });
        refused.forEach((resource, why) -> {
            if (Probe.isPerNode(resource)) return;
            sb.append(String.format(Locale.ROOT, "  %-14s REFUSED: %s%n", resource, why));
        });

        long fitted = byResource.keySet().stream().filter(Probe::isPerNode).count();
        long declined = refused.keySet().stream().filter(Probe::isPerNode).count();
        if (fitted + declined > 0)
            sb.append(String.format(Locale.ROOT, "  %-14s %d fitted, %d refused — in the trace, per machine%n",
                    "per machine", fitted, declined));
        return sb.toString();
    }
}
