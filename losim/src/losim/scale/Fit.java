package losim.scale;

import java.util.ArrayList;
import java.util.List;

/**
 * How a resource grows with the thing that drives it.
 *
 * <p>Every resource is fitted as {@code demand = c + a*n^beta}, never as a pure
 * power law. Even wire bytes, which "should" be linear, measures an exponent near
 * 0.91 on a small ladder — because per-call overhead is a real constant that is
 * proportionally huge when {@code n} is small. Folding that constant into the
 * exponent and then extrapolating it is wrong in both directions, and the probe
 * grid runs at exactly the small {@code n} where the mistake is largest.
 */
public final class Fit {
    private Fit() {}

    /**
     * A fitted law, and how much of it to believe.
     *
     * @param wobble how far {@code beta} moved across independent seed sets of the
     *               same workload, or −1 if reproducibility was never measured.
     *               This, not {@code r2}, is what decides whether a projection may
     *               be made at all.
     */
    public record Law(String resource, String variable,
                      double fixed, double coefficient, double beta,
                      double r2, double wobble) {

        /** Projected demand at a given value of the independent variable. */
        public double at(double n) { return fixed + coefficient * Math.pow(n, beta); }

        @Override public String toString() {
            return String.format("%s = %.4g + %.4g * %s^%.3f  (R2 %.4f%s)",
                    resource, fixed, coefficient, variable, beta, r2,
                    wobble < 0 ? "" : String.format(", wobble %.3f", wobble));
        }
    }

    /** Fits {@code log v = log a + beta log n}. Returns {beta, R^2, a}. */
    public static double[] power(double[] n, double[] v) {
        int m = n.length;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < m; i++) {
            double x = Math.log(n[i]), y = Math.log(v[i]);
            sx += x; sy += y; sxx += x * x; sxy += x * y;
        }
        double beta = (m * sxy - sx * sy) / (m * sxx - sx * sx);
        double a = (sy - beta * sx) / m;
        double ssTot = 0, ssRes = 0, my = sy / m;
        for (int i = 0; i < m; i++) {
            double y = Math.log(v[i]), yh = a + beta * Math.log(n[i]);
            ssTot += (y - my) * (y - my);
            ssRes += (y - yh) * (y - yh);
        }
        return new double[]{beta, ssTot == 0 ? 1 : 1 - ssRes / ssTot, Math.exp(a)};
    }

    /**
     * Fits {@code c + a*n^beta}, searching for the fixed term.
     *
     * <p>The search is capped <b>well short of the smallest observation</b>.
     * Allowed to approach it, it drives every residual towards zero, where log-log
     * is degenerate and R² is meaninglessly high — a perfect fit to nothing at
     * all.
     */
    public static Law withFixedTerm(String resource, String variable, double[] n, double[] v) {
        double smallest = Double.MAX_VALUE;
        for (double x : v) smallest = Math.min(smallest, x);
        if (smallest <= 0 || n.length < 3) {
            double[] p = power(n, v);
            return new Law(resource, variable, 0, p[2], p[0], p[1], -1);
        }
        Law best = null;
        double bestErr = Double.MAX_VALUE;
        int steps = 400;
        for (int i = 0; i <= steps; i++) {
            double c = 0.80 * smallest * i / steps;
            var residual = new double[v.length];
            boolean usable = true;
            for (int j = 0; j < v.length; j++) {
                residual[j] = v[j] - c;
                if (residual[j] <= 0) { usable = false; break; }
            }
            if (!usable) continue;
            double[] p = power(n, residual);
            if (p[0] <= 0) continue;
            double err = 1 - p[1];
            if (err < bestErr) {
                bestErr = err;
                best = new Law(resource, variable, c, p[2], p[0], p[1], -1);
            }
        }
        if (best != null) return best;
        double[] p = power(n, v);
        return new Law(resource, variable, 0, p[2], p[0], p[1], -1);
    }

    /**
     * Whether the ladder bends.
     *
     * <p><b>R² cannot answer this.</b> On a workload whose reducer spills to disk
     * above a key count — memory climbing, then flat — R² over the whole ladder
     * falls only to about 0.74, a score a merely noisy linear workload reaches just
     * as easily. No threshold on R² separates bent from noisy.
     *
     * <p>Splitting the ladder does: the lower half fits β ≈ 0.60 and the upper half
     * β ≈ 0.00, which is unambiguous and interpretable. A divergence beyond
     * {@link #DISCONTINUITY} means the code behaves differently large than small,
     * and no extrapolation across it is meaningful.
     */
    public static final double DISCONTINUITY = 0.25;

    public static double halvesDiverge(double[] n, double[] v) {
        if (n.length < 4) return 0;
        return Math.abs(lowerBeta(n, v) - upperBeta(n, v));
    }

    /** The exponent fitted over the lower half of a ladder. */
    public static double lowerBeta(double[] n, double[] v) {
        int mid = n.length / 2;
        return power(java.util.Arrays.copyOfRange(n, 0, mid),
                     java.util.Arrays.copyOfRange(v, 0, mid))[0];
    }

    /** The exponent fitted over the upper half, overlapping by one so the halves meet. */
    public static double upperBeta(double[] n, double[] v) {
        int mid = n.length / 2;
        return power(java.util.Arrays.copyOfRange(n, mid - 1, n.length),
                     java.util.Arrays.copyOfRange(v, mid - 1, v.length))[0];
    }

    /**
     * How far an exponent moves when nothing has changed.
     *
     * <p>R² says how well a line went through the points it was given. It says
     * nothing about whether those points would land in the same place again — and
     * on a noisy measurement they will not. A law whose exponent cannot be
     * reproduced to better than its own effect size is not a law; it is a number
     * with a line through it, and the engine must refuse to project from it.
     *
     * @param betas one fitted exponent per independent seed set
     */
    public static double wobble(List<Double> betas) {
        if (betas.size() < 2) return -1;
        var s = new ArrayList<>(betas);
        return java.util.Collections.max(s) - java.util.Collections.min(s);
    }
}
