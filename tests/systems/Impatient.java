import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import lab.pb.Chunk;
import lab.pb.WorkerGrpc;
import losim.api.Cluster;
import losim.api.Job;

/**
 * Asks for something that takes 500 refMs and waits 200 for it.
 *
 * <p>The deadline is written in reference milliseconds like everything else, so
 * losim rescales it before gRPC ever sees it: at k_time 20 the client really waits
 * 10 ms and the handler really sleeps 25. Both sides compressed by the same factor
 * means the lesson — this deadline is too short — survives the compression.
 */
public final class Impatient implements Job {
    @Override public void run(Cluster cluster) {
        var channel = cluster.channelTo("srv");
        var request = Chunk.newBuilder().setText("anything").setLines(1).build();

        // Twice. The first call through a channel pays for every class on the path
        // being loaded and compiled, which is tens of milliseconds of host time and
        // has nothing to do with the deadline — so what gets measured is the second.
        String outcome = "";
        for (int attempt = 0; attempt < 2; attempt++) {
            var stub = WorkerGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(200, TimeUnit.MILLISECONDS);
            try {
                stub.map(request);
                outcome = "answered, which it should not have";
            } catch (StatusRuntimeException e) {
                outcome = "gave up: " + e.getStatus().getCode();
            }
        }
        cluster.done(outcome);
    }
}
