import losim.api.Cluster;
import losim.api.Job;

/**
 * A job that measures its own phases with the wrong clock.
 *
 * <p>A job is not a machine, but it runs on one — the first in the file — so its
 * allocation and its wall clock land on that machine's counters, and so does this.
 */
public final class ClockingJob implements Job {
    @Override public void run(Cluster cluster) {
        long began = System.currentTimeMillis();
        cluster.done("took " + (System.currentTimeMillis() - began) + "ms");
    }
}
