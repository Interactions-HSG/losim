import io.grpc.stub.StreamObserver;
import losim.api.Losim;
import losim.pb.Input;
import losim.pb.JobGrpc;
import losim.pb.Result;
import losim.pb.Workload;
import losim.t.Chunk;
import losim.t.Counts;
import losim.t.WorkerGrpc;

/** One call to a node whose handler makes another. */
public final class ForwardJob extends JobGrpc.JobImplBase {

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        Counts counted = WorkerGrpc.newBlockingStub(Losim.current().channelTo("front"))
                .map(Chunk.newBuilder().setText("a b a").setLines(1).build());
        var answer = Result.newBuilder();
        counted.getCountsMap().forEach((k, v) -> answer.putAnswer(k, String.valueOf(v)));
        out.onNext(answer.build());
        out.onCompleted();
    }
}
