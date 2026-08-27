import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A graph to rank: pages, and where they point.
 *
 * <p>Built by preferential attachment — a new page is more likely to link to a
 * page that is already linked to — because that is what makes PageRank worth
 * computing and what makes the run interesting. On a random graph every page ends
 * up with roughly the rank of every other, the ranks converge in two iterations,
 * and the partitions are all the same size. On a scale-free one there are a few
 * pages everything points at, convergence takes real iterations, and the
 * partition holding a hub is several times the size of the others.
 *
 * <p>Which is the same lesson the Zipf corpus teaches in {@link Corpus}, arriving
 * from the other direction: the interesting resource question is never the total,
 * it is the distribution.
 */
public final class Web {

    private final int[][] out;

    public Web(int pages, int linksPerPage, long seed) {
        var rng = new Random(seed);
        this.out = new int[pages][];

        // Every page that has been linked to, once per link — so drawing from this
        // list uniformly is drawing from the degree distribution. Crude, exact, and
        // the standard way to write preferential attachment without a tree.
        var targets = new ArrayList<Integer>(pages * linksPerPage);
        for (int i = 0; i < Math.min(pages, 8); i++) targets.add(i);

        for (int page = 0; page < pages; page++) {
            int links = 1 + rng.nextInt(Math.max(1, linksPerPage));
            var chosen = new ArrayList<Integer>(links);
            for (int k = 0; k < links; k++) {
                int to = targets.isEmpty() ? rng.nextInt(pages)
                                           : targets.get(rng.nextInt(targets.size()));
                if (to == page) to = (to + 1) % pages;
                if (!chosen.contains(to)) chosen.add(to);
            }
            out[page] = chosen.stream().mapToInt(Integer::intValue).toArray();
            for (int to : out[page]) targets.add(to);
        }
    }

    public int pages() { return out.length; }

    public int[] linksFrom(int page) { return out[page]; }

    /** Which reducer owns a page. The one line the whole fleet has to agree on. */
    public static int partition(int page, int parts) { return Math.floorMod(page, parts); }

    /** Degrees, biggest first — for a scenario that wants to say how skewed this graph is. */
    public List<Integer> degrees() {
        var in = new int[out.length];
        for (int[] links : out) for (int to : links) in[to]++;
        var list = new ArrayList<Integer>();
        for (int d : in) list.add(d);
        list.sort((a, b) -> b - a);
        return list;
    }
}
