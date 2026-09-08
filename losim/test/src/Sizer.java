import io.grpc.stub.StreamObserver;
import losim.pb.Input;
import losim.pb.JobGrpc;
import losim.pb.Result;
import losim.pb.Workload;

/**
 * A Job that does nothing but say what it was handed.
 *
 * <p>{@code Load} sees the whole declaration — source, unit and a count already
 * shrunk to this rung — and {@code Run} sees only the {@link Workload} that came
 * back. That is the split the scale model rests on, and the reason it is two rpcs
 * rather than one: {@code Run} cannot read the file, cannot see the source, and
 * cannot tell a probe run from the full one.
 */
public final class Sizer extends JobGrpc.JobImplBase {

    @Override public void load(Input in, StreamObserver<Workload> out) {
        // Deliberately fewer than asked for. A Load that drops malformed rows or
        // folds duplicates produces fewer, and the trace records both numbers —
        // because a Load that quietly returns a tenth of its count is how a fitted
        // exponent goes wrong for a reason nobody finds.
        out.onNext(Workload.newBuilder()
                .setCount(in.getCount() - 1)
                .setType(in.getUnit())
                .build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        out.onNext(Result.newBuilder()
                .putAnswer("count", String.valueOf(work.getCount()))
                .putAnswer("type", work.getType())
                .build());
        out.onCompleted();
    }
}
