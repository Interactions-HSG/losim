import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import losim.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one property the clock's calibration cannot lose.
 *
 * <p>losim divides every declared duration by a ratio it measures against the
 * host at startup. A mean over parks is destroyed by the host being busy: one
 * descheduled sample of 50 ms among two hundred parks of 0.05 ms adds five times
 * the measured quantity to their average. A mean-based correction would land
 * near double, every {@code @Takes} would sleep about half its declared length,
 * and the trace would look perfectly ordinary — the same jar and the same seed
 * billing a handler 200 refMs in the morning and 100 in the afternoon.
 *
 * <p>What makes this testable is that {@link Clock#summarise} takes the samples
 * rather than measuring them. A test that needed a genuinely overloaded machine
 * to fail is a test that would pass on every machine anybody ran it on, which is
 * what would let this go unnoticed.
 *
 * <p><b>None of this gates a run any more.</b> It did briefly: a calibration too
 * erratic, or too high to be a real timer, was refused outright. Then
 * {@link Clock#spend} was fixed to park a cost repeatedly until it is actually
 * paid, and the correction stopped deciding how much time a cost gets — five
 * 200 refMs costs take 1000 ms whether the correction is 1.0 or 20.0, and on a
 * host starved of every core. Refusing on these grounds would now stop runs that
 * would have been right. What is tested here is that the two conditions are
 * still told apart and still reported, because they explain a slow run;
 * {@code Clock.unpaidCosts()} is what says a figure is actually short.
 */
class ClockCalibrationTest {

    /** A host that overshoots by a steady ratio, which is what a quiet one does. */
    private static double[] parks(double targetNs, double ratio, int reps) {
        double[] out = new double[reps];
        for (int i = 0; i < reps; i++) {
            // A little spread, so the median is not trivially the only value present.
            out[i] = targetNs * ratio * (0.98 + 0.04 * ((i % 7) / 6.0));
        }
        return out;
    }

    private static Clock.Calibration summarise(double[]... perTarget) {
        var samples = new ArrayList<double[]>();
        var targets = new ArrayList<Double>();
        double[] ns = {0.05e6, 0.1e6, 0.25e6, 0.5e6, 1e6, 2e6};
        for (int i = 0; i < perTarget.length; i++) {
            samples.add(perTarget[i]);
            targets.add(ns[i]);
        }
        return Clock.summarise(samples, targets);
    }

    @Test
    @DisplayName("a quiet host is measured at the ratio it actually overshoots by")
    void quiet() {
        var c = summarise(parks(0.05e6, 1.28, 200), parks(0.1e6, 1.28, 200),
                          parks(0.25e6, 1.28, 200), parks(0.5e6, 1.28, 60),
                          parks(1e6, 1.28, 60), parks(2e6, 1.28, 60));
        assertEquals(1.28, c.correction(), 0.01);
        assertEquals(0.0, c.noise(), 1e-9, "nothing here is wild");
        assertTrue(c.quiet());
    }

    @Test
    @DisplayName("one descheduled park among two hundred does not move the correction")
    void oneOutlierChangesNothing() {
        // The exact shape of the bug: 0.05 ms parks, one of which took 50 ms.
        // Its contribution to a *mean* is 50ms/200 = 0.25 ms — five times the
        // 0.05 ms being measured — so a mean-based fit lands near 6x instead of
        // 1.28x. The median must not notice it at all.
        double[] withHiccup = parks(0.05e6, 1.28, 200);
        withHiccup[137] = 50e6;

        double mean = 0;
        for (double v : withHiccup) mean += v;
        mean /= withHiccup.length;
        assertTrue(mean / 0.05e6 > 4.0,
                "the sample set has to be one a mean would get wrong, or this proves nothing");

        var c = summarise(withHiccup, parks(0.1e6, 1.28, 200), parks(0.25e6, 1.28, 200),
                          parks(0.5e6, 1.28, 60), parks(1e6, 1.28, 60), parks(2e6, 1.28, 60));
        assertEquals(1.28, c.correction(), 0.01,
                "the median must be blind to a single absurd park");
    }

    @Test
    @DisplayName("a host that cannot hold a sleep is reported as noisy")
    void loadedHostIsNoticed() {
        // A fifth of the parks come back at four times their neighbours, which is
        // what eleven busy cores out of twelve actually produced.
        double[] starved = parks(0.05e6, 1.28, 200);
        for (int i = 0; i < starved.length; i += 5) starved[i] *= 4;

        var c = summarise(starved, parks(0.1e6, 1.28, 200), parks(0.25e6, 1.28, 200),
                          parks(0.5e6, 1.28, 60), parks(1e6, 1.28, 60), parks(2e6, 1.28, 60));
        assertTrue(c.noise() > Clock.NOISE_LIMIT,
                "a fifth of the parks being wild has to exceed the limit, or nothing is reported");
        assertFalse(c.quiet());
    }

    @Test
    @DisplayName("the limit sits in the empty gap between a quiet host and a starved one")
    void thresholdSeparates() {
        // Both sides of the threshold, from the measurements in Clock.NOISE_LIMIT:
        // a quiet host produced 0.0-0.4% wild parks and a starved one 14.7-21.8%.
        // A limit that did not separate those would refuse good runs or admit bad
        // ones, and this is the check that says which.
        assertTrue(Clock.NOISE_LIMIT > 0.004, "0.4% was observed on a host that measured correctly");
        assertTrue(Clock.NOISE_LIMIT < 0.147, "14.7% was observed on a host that measured wrongly");
    }

    @Test
    @DisplayName("of several attempts, the quietest is the one believed")
    void quietestWins() {
        // A host that was interrupted once and then settled. Taking the last, or
        // the first, would keep an answer the host itself disagrees with a
        // moment later.
        var rounds = java.util.List.of(
                new Clock.Calibration(2.31, 0.19),
                new Clock.Calibration(1.28, 0.004),
                new Clock.Calibration(1.91, 0.14));
        var picked = Clock.quietest(rounds);
        assertEquals(1.28, picked.correction(), 1e-9);
        assertTrue(picked.quiet(), "the quietest here is below the limit");
    }

    @Test
    @DisplayName("retrying does not launder a host that is starved every time")
    void retryingIsNotAnEscapeHatch() {
        // The failure mode a retry invites: trying until the answer is liked.
        // Every round is noisy, so the quietest is still noisy, and the run is
        // still refused — otherwise the guard would be dead code the moment it
        // was given a second chance.
        var rounds = java.util.List.of(
                new Clock.Calibration(2.31, 0.19),
                new Clock.Calibration(2.06, 0.15),
                new Clock.Calibration(1.98, 0.21));
        var picked = Clock.quietest(rounds);
        assertEquals(0.15, picked.noise(), 1e-9, "the least bad of them");
        assertFalse(picked.quiet(), "still noisy — three bad measurements are not one good one");
    }

    @Test
    @DisplayName("a slow but steady timer is told apart from a busy one, which spread cannot do")
    void biasIsNotVariance() {
        // Rosetta, measured: correction 6.579, noise 0.001 — quieter than some
        // idle native runs. Every check that looks at spread passes it, which is
        // how it served 53% of declared work under a trace saying trusted: true.
        var rosetta = new Clock.Calibration(6.579, 0.001);
        assertTrue(rosetta.quiet(), "nothing about its spread is wrong, and that was the trap");
        assertFalse(rosetta.plausible(), "its level is wrong, and that is the finding");
        assertFalse(rosetta.usable());

        // And the native hosts it must not catch.
        for (double c : new double[] {1.279, 1.281, 1.295, 1.333}) {
            var native_ = new Clock.Calibration(c, 0.0);
            assertTrue(native_.usable(), c + " is an ordinary host and must not be refused");
        }
    }

    @Test
    @DisplayName("the two findings are independent — busy and slow are different machines")
    void quietAndPlausibleAreSeparate() {
        assertFalse(new Clock.Calibration(1.28, 0.20).quiet(), "busy");
        assertTrue(new Clock.Calibration(1.28, 0.20).plausible(), "but its timer is fine");
        assertTrue(new Clock.Calibration(6.58, 0.001).quiet(), "still");
        assertFalse(new Clock.Calibration(6.58, 0.001).plausible(), "but its timer is not");
    }

    @Test
    @DisplayName("a host that never overshoots is not corrected upwards")
    void neverBelowOne() {
        var c = summarise(parks(0.05e6, 0.5, 200), parks(0.1e6, 0.5, 200),
                          parks(0.25e6, 0.5, 200), parks(0.5e6, 0.5, 60),
                          parks(1e6, 0.5, 60), parks(2e6, 0.5, 60));
        assertEquals(1.0, c.correction(), 1e-9, "dividing by less than one would lengthen every sleep");
    }
}
