import java.util.concurrent.TimeUnit;
import lab.pb.Chunk;
import lab.pb.Counts;
import lab.pb.Report;
import lab.pb.Severity;
import lab.pb.WorkerGrpc;
import losim.api.Cluster;
import losim.api.Job;

/** One client, one server, one unary call — and one message worth rendering. */
public final class OneCall implements Job {

    /** Fixed, so an assertion can rebuild it and check the bytes against the marshaller. */
    public static Chunk request() {
        return Chunk.newBuilder().setText("the cat sat on the mat").setLines(1).build();
    }

    /** An enum, a oneof and a repeated field: three ways a renderer can drift. */
    public static Report report() {
        return Report.newBuilder().setSeverity(Severity.WARN).setCode(42)
                .addTags("beta").addTags("alpha").build();
    }

    @Override public void run(Cluster cluster) {
        var stub = WorkerGrpc.newBlockingStub(cluster.channelTo("srv"))
                .withDeadlineAfter(2000, TimeUnit.MILLISECONDS);
        Counts counted = stub.map(request());
        Report noted = stub.note(report());
        cluster.done(counted.getCountsMap() + " / " + noted.getTagsList());
    }
}
