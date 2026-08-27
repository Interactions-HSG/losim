import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The text the fleet counts: words drawn from a Zipf distribution.
 *
 * <p>Not a detail. A word count over uniformly random words has a vocabulary that
 * grows as fast as the text does, so every resource in the run scales the same way
 * and every question worth asking answers itself. Drawn from Zipf, the vocabulary
 * follows Heaps' law — {@code V(n) ~ K·n^b} with {@code b < 1} — and the three
 * resources come apart: the bytes on the wire track volume, the mapper's disk
 * tracks volume, and the shuffler's memory tracks <i>distinct keys</i>, which is a
 * sublinear function of volume. That is the difference between a reducer that fits
 * and one that does not, and it is invisible on uniform data.
 *
 * <p>Zipf also makes the partitions uneven, which is the other half of the lesson:
 * hashing keys into R buckets does not give R equal buckets, and the reducer that
 * draws "the" is the one that runs out of memory.
 *
 * <p>Seeded from the scenario, so a sweep of seeds varies the data and not only
 * the weather.
 */
public final class Corpus {

    private final String[] vocab;
    private final double[] cdf;
    private final Random rng;

    public Corpus(int vocabSize, double skew, long seed) {
        this.rng = new Random(seed);
        this.vocab = new String[vocabSize];
        this.cdf = new double[vocabSize];
        double total = 0;
        for (int i = 0; i < vocabSize; i++) {
            vocab[i] = WORDS[i % WORDS.length] + (i < WORDS.length ? "" : String.valueOf(i / WORDS.length));
            total += 1.0 / Math.pow(i + 1, skew);
            cdf[i] = total;
        }
        for (int i = 0; i < vocabSize; i++) cdf[i] /= total;
    }

    /** Real words for the first few hundred, so a trace reads like text and not like ids. */
    private static final String[] WORDS = {
        "the", "cat", "sat", "on", "mat", "dog", "log", "bird", "and", "a", "sang",
        "watched", "ran", "slept", "under", "over", "tree", "house", "river", "hill",
        "stone", "wind", "rain", "night", "morning", "field", "road", "door", "fire",
        "water", "bread", "salt", "iron", "glass", "paper", "thread", "wheel", "boat",
    };

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

    /**
     * Which partition a key belongs to.
     *
     * <p>The one line every machine in the fleet has to agree on, which is why it
     * lives here rather than in three of them. {@code floorMod}, not {@code %}:
     * a negative hash code with {@code %} gives a negative partition, and the bug
     * is a chunk of the vocabulary that silently never arrives anywhere.
     */
    public static int partition(String key, int parts) {
        return Math.floorMod(key.hashCode(), parts);
    }
}
