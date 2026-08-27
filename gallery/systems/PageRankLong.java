/**
 * PageRank, run for ten rounds instead of two.
 *
 * <p>The only difference, and the whole experiment. Everything that grows between
 * this and {@link PageRank} grows because of iteration and nothing else: the same
 * graph, the same fleet, the same partitioning, the same arithmetic per round.
 *
 * <p>Divide the disk written here by the disk written there and you have the
 * answer to whether MapReduce suits iterative algorithms, in the only form that
 * settles an argument.
 */
public final class PageRankLong extends PageRank {
    @Override protected int iterations() { return 10; }
}
