import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.DoubleUnaryOperator;
import dissaly.scale.Grid;
import dissaly.scale.Laws;
import dissaly.scale.Probe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a run's duration grows, and whether the engine can tell one shape from another.
 *
 * <p>Time is the resource a projection is most often wanted for and the one it is
 * least safe to guess at. A design whose makespan is flat in the workload and one
 * whose makespan is quadratic in it look identical at the size a laptop can run —
 * a few hundred milliseconds either way — and differ by a factor of a hundred
 * million at the size somebody is about to pay for. Getting that exponent right,
 * or refusing to name one, is the whole of what scaled mode is for.
 *
 * <p><b>The ladders here are built rather than measured.</b> Every other test of
 * the scaler runs a simulation and reads what comes out, which answers "does this
 * work on the cases we have" and cannot answer "does it work on the case we do
 * not". A quadratic makespan is not something the gallery contains and not
 * something a scenario can be asked to produce on demand; written down, it is four
 * numbers. So the ladder is constructed to a known law and the test asks what the
 * fitter makes of it — which also means the answer is exact, and a failure is
 * about the fitter rather than about a machine being busy.
 *
 * <p>The claim under test is the projection, not the coefficients. How a fit
 * splits itself between a fixed term and an exponent is its own business and
 * changes with the fitter; what a reader is shown is what the law says at full
 * size, and that is what these assert on.
 */
class ScaleTimeLawTest {

    /** The rungs the real engine climbs, so these ladders bend where a real one would. */
    private static final long[] RUNGS = {1000, 2000, 4000, 8000};

    /** Four, because a wobble is measured between two independent sets of two. */
    private static final int SEEDS = 4;

    /** How far a projection reaches here. Large, so a wide error bar is refused rather than reported. */
    private static final double REACH = 12500;

    // ------------------------------------------------------------------ scaffolding

    private static Probe probeAt(long units, long seed, double makespan) {
        var variables = new TreeMap<String, Double>();
        variables.put("units", (double) units);
        variables.put("workers", 4.0);
        var resources = new TreeMap<String, Double>();
        resources.put(Probe.TIME, makespan);
        return new Probe(units, 4, 0, seed, variables, resources, new TreeMap<>(), true, null);
    }

    /**
     * A grid whose data ladder follows one law exactly, at every seed.
     *
     * <p>No cluster ladder and no weather: both are cross-checks on attribution,
     * and what is being asked here is whether an exponent comes back at all. An
     * empty fault column also means the amplification term is 1, so the projection
     * is the law and nothing else.
     */
    private static Grid ladder(DoubleUnaryOperator law) {
        return ladder((units, seed) -> law.applyAsDouble(units));
    }

    private interface Rung {
        double at(long units, int seed);
    }

    private static Grid ladder(Rung law) {
        var data = new ArrayList<List<Probe>>();
        for (long units : RUNGS) {
            var rung = new ArrayList<Probe>();
            for (int seed = 0; seed < SEEDS; seed++) rung.add(probeAt(units, seed, law.at(units, seed)));
            data.add(rung);
        }
        return new Grid(data, List.of(), List.of(), List.of(), List.of());
    }

    /** What the fitted law says at a given size, or a failure naming why it said nothing. */
    private static double project(Laws laws, double units) {
        var refusal = laws.refused().get(Probe.TIME);
        assertNull(refusal, "the law was refused rather than fitted: " + refusal);
        var p = laws.project(Probe.TIME, units);
        assertTrue(p.isPresent(), "fitted but projected nothing, which should be impossible");
        return p.getAsDouble();
    }

    /**
     * That a projection grew by the factor the shape demands.
     *
     * <p>Stated as the growth between two sizes rather than as an absolute, because
     * growth is the claim: a law that is right about the shape and wrong about the
     * constant is a different bug from one that is wrong about the shape, and only
     * the second makes a design look like it scales when it does not.
     */
    private static void growsBy(Laws laws, double expected, String shape) {
        double at8k = project(laws, 8000);
        double at80k = project(laws, 80000);
        double actual = at80k / at8k;
        assertEquals(expected, actual, expected * 0.08,
                shape + ": ten times the workload should cost " + expected + " times the time,"
                + " and this law says " + String.format("%.2f", actual) + ". An exponent wrong here"
                + " is wrong by that factor again at every further decade");
    }

    // ---------------------------------------------------------------- the shapes

    @Test
    @DisplayName("time flat in the workload stays flat: a design whose duration is all fixed overhead")
    void flat() {
        var laws = Laws.fit(ladder(units -> 250.0), REACH);
        growsBy(laws, 1.0, "flat");
    }

    @Test
    @DisplayName("time linear in the workload projects linearly: ten times the work, ten times the wait")
    void linear() {
        var laws = Laws.fit(ladder(units -> 0.5 * units), REACH);
        assertEquals(1.0, laws.law(Probe.TIME).beta(), 0.05, "a linear law should have an exponent of 1");
        growsBy(laws, 10.0, "linear");
    }

    @Test
    @DisplayName("time square in the workload projects as the square: ten times the work, a hundred times the wait")
    void squared() {
        var laws = Laws.fit(ladder(units -> 1e-4 * units * units), REACH);
        assertEquals(2.0, laws.law(Probe.TIME).beta(), 0.05, "a square law should have an exponent of 2");
        growsBy(laws, 100.0, "squared");
        // The reason this shape is worth a test of its own: at the top of the probe
        // ladder it is 6.4 seconds, which is an unremarkable run. At ten thousand
        // times that workload it is two years. Nothing in the measurement looks
        // alarming; only the exponent does.
        assertTrue(project(laws, 8000 * REACH) > 1e9,
                "a quadratic design at 12,500x should project an unmistakably large number");
    }

    @Test
    @DisplayName("time as the root of the workload projects sublinearly: the shape of work that fans out")
    void root() {
        var laws = Laws.fit(ladder(units -> 30.0 * Math.sqrt(units)), REACH);
        assertEquals(0.5, laws.law(Probe.TIME).beta(), 0.05, "a root law should have an exponent of a half");
        growsBy(laws, Math.sqrt(10), "root");
    }

    @Test
    @DisplayName("a cubic workload is not mistaken for a square one")
    void cubed() {
        var laws = Laws.fit(ladder(units -> 1e-8 * units * units * units), REACH);
        assertEquals(3.0, laws.law(Probe.TIME).beta(), 0.05);
        growsBy(laws, 1000.0, "cubic");
    }

    // ------------------------------------------------------- and what is refused

    @Test
    @DisplayName("a ladder that bends is refused, and the refusal says so rather than averaging the two halves")
    void bendsAreRefused() {
        // Flat while the pieces are few, linear once they are not — a threshold, and
        // the single most common way a small measurement misleads about a large run.
        var laws = Laws.fit(ladder(units -> units <= 2000 ? 200.0 : 0.1 * units), REACH);

        String why = laws.refused().get(Probe.TIME);
        assertNotNull(why, "a bent ladder must be refused: no exponent describes both halves,"
                + " and the one that fits neither would still project confidently");
        assertTrue(why.contains("bends"), "the refusal should name the bend, and instead said: " + why);
        assertNull(laws.law(Probe.TIME), "a refused resource must carry no law at all");
        assertTrue(laws.project(Probe.TIME, 8000 * REACH).isEmpty(),
                "a refused resource must project nothing — an absent number is the result");
    }

    @Test
    @DisplayName("a straight ladder with noise on it is still fitted: noise is not a bend")
    void noiseIsNotABend() {
        // The same linear law with a few percent of jitter, which is what a real
        // measurement looks like. Refusing this would make the engine useless on
        // every real system, and the discontinuity test exists precisely so that
        // noise and a threshold are told apart rather than lumped together.
        var jitter = new double[]{1.03, 0.97, 1.02, 0.98};
        var laws = Laws.fit(ladder((units, seed) -> 0.5 * units * jitter[seed % jitter.length]), REACH);

        assertNull(laws.refused().get(Probe.TIME),
                "a noisy straight line was refused as though it bent, which would refuse"
                + " every honest measurement ever taken");
        growsBy(laws, 10.0, "noisy linear");
    }

    @Test
    @DisplayName("an exponent that will not reproduce between seed sets is refused as unreproducible, not as a bend")
    void unreproducibleExponentsAreRefusedAsSuch() {
        // Two independent pairs of seeds that disagree about the shape itself: one
        // pair linear, the other three-halves. The median across all four is a
        // perfectly smooth curve, so nothing about the ladder looks bent — the only
        // evidence that this law means nothing is that it does not come back the
        // same way twice.
        var laws = Laws.fit(ladder((units, seed) ->
                seed < 2 ? 0.5 * units : 0.5 * Math.pow(units, 1.5) / Math.pow(8000, 0.5)), REACH);

        String why = laws.refused().get(Probe.TIME);
        assertNotNull(why, "an exponent that moves between seed sets cannot support a projection"
                + " over a factor of " + (long) REACH);
        assertTrue(why.contains("moves by"),
                "it should be refused as unreproducible rather than as a discontinuity —"
                + " an exponent that cannot be reproduced has no standing to claim a bend"
                + " either. It said: " + why);
    }

    // ------------------------------------------------------------ nothing at all

    @Test
    @DisplayName("a resource that is zero everywhere projects zero, rather than being refused for having nothing to fit")
    void alwaysZeroProjectsZero() {
        var laws = Laws.fit(ladder(units -> 0.0), REACH);

        assertNull(laws.refused().get(Probe.TIME),
                "zero at every rung is not an absence of measurement, it is a measurement."
                + " A machine that writes no disk writes none at any size, and refusing that"
                + " sends a reader looking for a failure that did not happen");
        assertEquals(0.0, project(laws, 8000 * REACH), 1e-9,
                "the projection of nothing is nothing, at any size");
        assertEquals(1.0, laws.errorBars().getOrDefault(Probe.TIME, 0.0), 1e-9,
                "there is nothing to be uncertain about, so the error bar is exactly 1");
    }

    @Test
    @DisplayName("a resource that is zero on some rungs and not others is still refused: that is a threshold, not a constant")
    void zeroOnSomeRungsIsRefused() {
        // Nothing until the workload is big enough, then something. No power law
        // passes through it, and the interesting part — where it switches on — is
        // below the smallest rung the ladder can see.
        var laws = Laws.fit(ladder(units -> units <= 2000 ? 0.0 : 0.1 * units), REACH);

        String why = laws.refused().get(Probe.TIME);
        assertNotNull(why, "a resource that switches on partway up the ladder cannot be fitted:"
                + " the threshold is the whole behaviour and the ladder cannot see past it");
        assertFalse(why.contains("never measured above zero"),
                "it was measured above zero — on half the rungs — so that is the wrong reason"
                + " and would send somebody looking at the wrong thing. It said: " + why);
    }

    // -------------------------------------------------------- shapes side by side

    @Test
    @DisplayName("the same measurements at the top of the ladder, four different futures")
    void indistinguishableSmallDivergeLarge() {
        // Every one of these passes through the same point at 8,000 units. That is
        // the case the whole ladder exists for: at the size that can be measured
        // they agree, and the only thing separating them is the exponent.
        double atTop = 1000.0;
        var shapes = new java.util.LinkedHashMap<String, DoubleUnaryOperator>();
        shapes.put("flat",    units -> atTop);
        shapes.put("root",    units -> atTop * Math.sqrt(units / 8000.0));
        shapes.put("linear",  units -> atTop * (units / 8000.0));
        shapes.put("squared", units -> atTop * Math.pow(units / 8000.0, 2));

        var projected = new java.util.LinkedHashMap<String, Double>();
        shapes.forEach((name, law) -> {
            var laws = Laws.fit(ladder(law), REACH);
            assertEquals(atTop, project(laws, 8000), atTop * 0.05,
                    name + " should agree with the others at the size that was measured");
            projected.put(name, project(laws, 8000 * REACH));
        });

        var order = List.copyOf(projected.keySet());
        for (int i = 1; i < order.size(); i++) {
            double lower = projected.get(order.get(i - 1)), higher = projected.get(order.get(i));
            assertTrue(higher > lower * 10,
                    order.get(i) + " projects " + String.format("%.3g", higher) + " and "
                    + order.get(i - 1) + " projects " + String.format("%.3g", lower)
                    + " — four laws that were within 5% of each other at 8,000 units have to"
                    + " come apart by orders of magnitude at 100,000,000, or the ladder is"
                    + " not distinguishing them and a reader is being told the same thing"
                    + " about four systems that behave nothing alike");
        }
    }
}
