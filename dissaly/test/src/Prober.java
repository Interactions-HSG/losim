import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import dissaly.api.Dissaly;
import dissaly.pb.Input;
import dissaly.pb.JobGrpc;
import dissaly.pb.Result;
import dissaly.pb.Workload;
import dissaly.t.Chunk;
import dissaly.t.WorkerGrpc;

/**
 * A Job that calls every peer once and says what came back.
 *
 * <p>For the rpc-level failures, which are the only thing in dissaly a caller finds
 * out about through the <i>status</i> of its own call rather than through the
 * trace. So the answer is a line per peer — {@code w0=OK}, {@code w1=UNAVAILABLE}
 * — which is what lets a test assert that one replica is bad and its siblings are
 * not.
 *
 * <p>A short deadline on purpose: a dropped request never arrives, so the only
 * thing that ever ends that call is the caller's own clock.
 */
public final class Prober extends JobGrpc.JobImplBase {

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).setType(in.getUnit()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        var here = Dissaly.current();
        var said = new ArrayList<String>();
        List<String> peers = here.peersServing("Worker");
        for (String peer : peers) {
            var stub = WorkerGrpc.newBlockingStub(here.channelTo(peer))
                    .withDeadlineAfter(200, TimeUnit.MILLISECONDS);
            try {
                stub.map(Chunk.newBuilder().setText("a b c").setLines(3).build());
                said.add(peer + "=OK");
            } catch (StatusRuntimeException e) {
                said.add(peer + "=" + e.getStatus().getCode().name());
            }
        }
        var answer = Result.newBuilder();
        for (String s : said) answer.putAnswer(s.split("=")[0], s.split("=")[1]);
        answer.putAnswer("peers", String.valueOf(peers.size()));
        out.onNext(answer.build());
        out.onCompleted();
    }
}
