import io.grpc.stub.StreamObserver;
import dissaly.api.Dissaly;
import dissaly.pb.Input;
import dissaly.pb.JobGrpc;
import dissaly.pb.Result;
import dissaly.pb.Workload;
import dissaly.t.Chunk;
import dissaly.t.Counts;
import dissaly.t.WorkerGrpc;

/** One call to a node whose handler makes another. */
public final class ForwardJob extends JobGrpc.JobImplBase {

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        Counts counted = WorkerGrpc.newBlockingStub(Dissaly.current().channelTo("front"))
                .map(Chunk.newBuilder().setText("a b a").setLines(1).build());
        var answer = Result.newBuilder();
        counted.getCountsMap().forEach((k, v) -> answer.putAnswer(k, String.valueOf(v)));
        out.onNext(answer.build());
        out.onCompleted();
    }
}
