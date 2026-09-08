import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import losim.api.Losim;
import losim.pb.Input;
import losim.pb.JobGrpc;
import losim.pb.Result;
import losim.pb.Workload;
import losim.t.*;

/**
 * Issues every call at once and waits for all of them.
 *
 * <p>A workload whose makespan is decided by how many cores the system has, not
 * by how much work there is — which is exactly the case a uniform factor gets
 * wrong. Four calls into eight cores take one wave; sixteen take two. Multiplying
 * the first run by four says eight.
 */
public final class BatchJob extends JobGrpc.JobImplBase {

    @Override public void load(Input in, StreamObserver<Workload> out) {
        // However many the simulation asked for, already shrunk to this rung. A
        // system of eight cores is then observed under-saturated at one size and
        // saturated at another, which is what the schedule tests need — and the
        // ratio is in the file, where it can be changed without recompiling.
        out.onNext(Workload.newBuilder().setCount(in.getCount()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) throws RuntimeException {
        var here = Losim.current();
        var workers = here.peersServing("Volley");
        int calls = (int) work.getCount();
        var done = new CountDownLatch(calls);
        for (int i = 0; i < calls; i++) {
            String peer = workers.get(i % workers.size());
            VolleyGrpc.newStub(here.channelTo(peer))
                    .withDeadlineAfter(60_000, TimeUnit.MILLISECONDS)
                    .poll(Ping.newBuilder().setSeq(i).setFrom("job").build(),
                          new StreamObserver<Empty>() {
                              @Override public void onNext(Empty e) { }
                              @Override public void onError(Throwable t) { done.countDown(); }
                              @Override public void onCompleted() { done.countDown(); }
                          });
        }
        try { done.await(120, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        out.onNext(Result.newBuilder()
                .putAnswer("calls", String.valueOf(calls))
                .putAnswer("nodes", String.valueOf(workers.size()))
                .build());
        out.onCompleted();
    }
}
