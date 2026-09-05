import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import lab.pb.Chunk;
import lab.pb.WorkerGrpc;
import losim.api.Cluster;
import losim.api.Job;

/**
 * The same call twice, against a deadline that can be met and one that cannot.
 *
 * <p>Forty thousand records at 0.02 refMs each is 800, plus 2 fixed. So 2000 refMs
 * is enough and 600 is not — and neither of those is knowable from the fixed cost
 * alone, which is the whole point: 2 refMs is under both.
 *
 * <p>Both in one run so that the case tests the claim in both directions. A check
 * that only ever fires proves nothing about when it should stay quiet.
 */
public final class Budget implements Job {
    @Override public void run(Cluster cluster) {
        var channel = cluster.channelTo("srv");
        var request = Chunk.newBuilder().setText("anything").setLines(40_000).build();
        var said = new StringBuilder();
        for (int refMs : new int[] {2000, 600}) {
            var stub = WorkerGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(refMs, TimeUnit.MILLISECONDS);
            try {
                stub.map(request);
                said.append(refMs).append(":answered ");
            } catch (StatusRuntimeException e) {
                said.append(refMs).append(':').append(e.getStatus().getCode()).append(' ');
            }
        }
        cluster.done(said.toString().trim());
    }
}
