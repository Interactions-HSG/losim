package lab;

import losim.api.Cluster;
import losim.api.Job;

/**
 * A lab from before 2.0.0: the workload size is read from the cluster, and the
 * shape of it is a constant beside the loop.
 */
public final class Filler implements Job {

    private static final int ITEMS_UNSCALED = 240;

    @Override public void run(Cluster cluster) throws Exception {
        long asked = cluster.records();
        int items = asked > 1 ? (int) asked : ITEMS_UNSCALED;
        for (int i = 0; i < items; i++) cluster.log("item " + i);
    }
}
