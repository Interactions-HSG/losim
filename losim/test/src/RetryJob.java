import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import dissaly.api.Dissaly;
import dissaly.pb.Input;
import dissaly.pb.JobGrpc;
import dissaly.pb.Result;
import dissaly.pb.Workload;
import dissaly.t.Ping;
import dissaly.t.VolleyGrpc;

/** One call to one node, against a handler that has been told to fail twice. */
public final class RetryJob extends JobGrpc.JobImplBase {

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        var here = Dissaly.current();
        String peer = here.peersServing("Volley").get(0);
        VolleyGrpc.newBlockingStub(here.channelTo(peer))
                .withDeadlineAfter(2000, TimeUnit.MILLISECONDS)
                .poll(Ping.newBuilder().setSeq(1).setFrom("job").build());
        out.onNext(Result.newBuilder().putAnswer("state", "answered").build());
        out.onCompleted();
    }
}
