import java.util.concurrent.TimeUnit;
import losim.api.Cluster;
import losim.api.Job;
import losim.t.Ping;
import losim.t.VolleyGrpc;

/** One call to one machine, against a handler that has been told to fail twice. */
public final class RetryJob implements Job {
    @Override public void run(Cluster cluster) {
        String peer = cluster.serving("Volley").get(0);
        VolleyGrpc.newBlockingStub(cluster.channelTo(peer))
                .withDeadlineAfter(2000, TimeUnit.MILLISECONDS)
                .poll(Ping.newBuilder().setSeq(1).setFrom("job").build());
        cluster.done("answered");
    }
}
