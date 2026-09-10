import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import lab.pb.Chunk;
import lab.pb.Counts;
import lab.pb.Report;
import lab.pb.Severity;
import lab.pb.WorkerGrpc;
import dissaly.api.Dissaly;
import dissaly.pb.Input;
import dissaly.pb.JobGrpc;
import dissaly.pb.Result;
import dissaly.pb.Workload;

/** One client, one server, one unary call — and one message worth rendering. */
public final class OneCall extends JobGrpc.JobImplBase {

    /** Fixed, so an assertion can rebuild it and check the bytes against the marshaller. */
    public static Chunk request() {
        return Chunk.newBuilder().setText("the cat sat on the mat").setLines(1).build();
    }

    /** An enum, a oneof and a repeated field: three ways a renderer can drift. */
    public static Report report() {
        return Report.newBuilder().setSeverity(Severity.WARN).setCode(42)
                .addTags("beta").addTags("alpha").build();
    }

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        var stub = WorkerGrpc.newBlockingStub(Dissaly.current().channelTo("srv"))
                .withDeadlineAfter(2000, TimeUnit.MILLISECONDS);
        Counts counted = stub.map(request());
        Report noted = stub.note(report());
        out.onNext(Result.newBuilder()
                .putAnswer("counts", String.valueOf(counted.getCountsMap()))
                .putAnswer("tags", String.valueOf(noted.getTagsList()))
                .build());
        out.onCompleted();
    }
}
