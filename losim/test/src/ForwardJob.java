import losim.api.Cluster;
import losim.api.Job;
import losim.t.Chunk;
import losim.t.Counts;
import losim.t.WorkerGrpc;

/** One call to a machine whose handler makes another. */
public final class ForwardJob implements Job {
    @Override public void run(Cluster cluster) {
        Counts out = WorkerGrpc.newBlockingStub(cluster.channelTo("front"))
                .map(Chunk.newBuilder().setText("a b a").setLines(1).build());
        cluster.done(out.getCountsMap());
    }
}
