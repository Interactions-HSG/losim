import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Text whose vocabulary grows more slowly than its length.
 *
 * <p>The scaler engine's central claim is that different resources scale with
 * different exponents — volume linearly, distinct keys sublinearly — and that claim
 * can only be tested against a workload where it is actually true. Words drawn from
 * a Zipf distribution give Heaps' law, {@code V(n) ~ K n^b} with {@code b < 1}.
 * Uniformly random words would give {@code b = 1} for everything and every case
 * below would pass without testing anything.
 *
 * <p>Seeded from the scenario, so a sweep varies the data and not only the weather.
 */
public final class Zipf {

    private final String[] vocab;
    private final double[] cdf;
    private final Random rng;

    public Zipf(int vocabSize, double s, long seed) {
        this.rng = new Random(seed);
        this.vocab = new String[vocabSize];
        this.cdf = new double[vocabSize];
        double total = 0;
        for (int i = 0; i < vocabSize; i++) {
            vocab[i] = "w" + i;
            total += 1.0 / Math.pow(i + 1, s);
            cdf[i] = total;
        }
        for (int i = 0; i < vocabSize; i++) cdf[i] /= total;
    }

    public String word() {
        double u = rng.nextDouble();
        int lo = 0, hi = cdf.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cdf[mid] < u) lo = mid + 1; else hi = mid;
        }
        return vocab[lo];
    }

    /** {@code lines} lines of {@code wordsPerLine} words each. */
    public List<String> lines(int lines, int wordsPerLine) {
        var out = new ArrayList<String>(lines);
        var sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.setLength(0);
            for (int w = 0; w < wordsPerLine; w++) {
                if (w > 0) sb.append(' ');
                sb.append(word());
            }
            out.add(sb.toString());
        }
        return out;
    }
}
