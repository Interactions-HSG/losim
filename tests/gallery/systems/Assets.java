import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Frames whose catalogue of source assets grows more slowly than the frame count.
 *
 * <p>A thumbnail service does not see a fresh image every time. Popular assets
 * come back over and over and rare ones almost never, so the number of
 * <i>distinct</i> assets a renderer has ever held grows as a sublinear power of
 * the frames it has processed, while the volume it has written grows linearly.
 *
 * <p>That gap is the whole point. Memory follows the catalogue, disk follows the
 * volume, and the two part company as the workload grows. Draw asset ids
 * uniformly instead and both exponents are 1, every resource looks alike, and a
 * projection cannot be shown to be wrong.
 *
 * <p>Seeded from the simulation, so changing the seed changes the data and not
 * only the weather.
 */
public final class Assets {

    private final String[] ids;
    private final double[] cdf;
    private final Random rng;

    public Assets(int catalogue, double skew, long seed) {
        this.rng = new Random(seed);
        this.ids = new String[catalogue];
        this.cdf = new double[catalogue];
        double total = 0;
        for (int i = 0; i < catalogue; i++) {
            ids[i] = "a" + i;
            total += 1.0 / Math.pow(i + 1, skew);
            cdf[i] = total;
        }
        for (int i = 0; i < catalogue; i++) cdf[i] /= total;
    }

    /** One asset id, drawn from the popularity curve. */
    public String asset() {
        double u = rng.nextDouble();
        int lo = 0, hi = cdf.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cdf[mid] < u) lo = mid + 1; else hi = mid;
        }
        return ids[lo];
    }

    /** {@code frames} asset ids, in order. */
    public List<String> frames(int frames) {
        var out = new ArrayList<String>(frames);
        for (int i = 0; i < frames; i++) out.add(asset());
        return out;
    }
}
