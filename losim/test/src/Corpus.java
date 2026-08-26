/** Zipf text, so vocabulary grows more slowly than length. */
import java.util.*;

/**
 * Text whose vocabulary grows more slowly than its length.
 *
 * The scaler engine's central claim is that resources scale with different
 * exponents — volume linearly, distinct keys sublinearly. That claim can only be
 * tested against a workload where it is actually true, so the corpus is drawn
 * from a Zipf distribution, which produces Heaps' law: V(n) ~ K * n^beta with
 * beta < 1. Uniformly random words would give beta = 1 and prove nothing.
 */
public final class Corpus {
    private final double[] cdf;
    private final String[] vocab;
    private final Random rng;

    public Corpus(int vocabSize, double zipfS, long seed) {
        this.rng = new Random(seed);
        this.vocab = new String[vocabSize];
        this.cdf = new double[vocabSize];
        double sum = 0;
        for (int i = 0; i < vocabSize; i++) {
            vocab[i] = "w" + i;
            sum += 1.0 / Math.pow(i + 1, zipfS);
            cdf[i] = sum;
        }
        for (int i = 0; i < vocabSize; i++) cdf[i] /= sum;
    }

    public String word() {
        double u = rng.nextDouble();
        int lo = 0, hi = cdf.length - 1;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (cdf[mid] < u) lo = mid + 1; else hi = mid; }
        return vocab[lo];
    }

    /** `records` lines of `wordsPerLine` words each. */
    public List<String> lines(int records, int wordsPerLine) {
        var out = new ArrayList<String>(records);
        var sb = new StringBuilder();
        for (int i = 0; i < records; i++) {
            sb.setLength(0);
            for (int w = 0; w < wordsPerLine; w++) { if (w > 0) sb.append(' '); sb.append(word()); }
            out.add(sb.toString());
        }
        return out;
    }

}
