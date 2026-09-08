import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import losim.api.Losim;
import losim.pb.Input;
import losim.pb.JobGrpc;
import losim.pb.Result;
import losim.pb.Workload;
import losim.t.Ping;
import losim.t.VolleyGrpc;

/** One call to one node, against a handler that has been told to fail twice. */
public final class RetryJob extends JobGrpc.JobImplBase {

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        var here = Losim.current();
        String peer = here.peersServing("Volley").get(0);
        VolleyGrpc.newBlockingStub(here.channelTo(peer))
                .withDeadlineAfter(2000, TimeUnit.MILLISECONDS)
                .poll(Ping.newBuilder().setSeq(1).setFrom("job").build());
        out.onNext(Result.newBuilder().putAnswer("state", "answered").build());
        out.onCompleted();
    }
}
