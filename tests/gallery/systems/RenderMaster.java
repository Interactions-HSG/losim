import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import losim.api.Losim;
import losim.pb.Input;
import losim.pb.JobGrpc;
import losim.pb.Result;
import losim.pb.Workload;
import thumbs.pb.Batch;
import thumbs.pb.Nothing;
import thumbs.pb.Sizes;
import thumbs.pb.ThumbnailerGrpc;

/**
 * Where the work starts: a thumbnail service, sized by whatever it is asked for.
 *
 * <p>{@code Load} is off the clock and hands over one number — how many frames.
 * {@code Run} is the simulation, and everything it does is measured. Because the
 * size arrives in the {@link Workload} rather than living in this class, the
 * engine can turn it down and measure a smaller version of the same design; a job
 * that held its own size would have nothing to turn.
 *
 * <p>Two stretches, deliberately of different shapes. Handing out batches fans out
 * over every renderer at once, so it is the part that gets shorter when the
 * cluster grows. Collecting the totals is one call per renderer and a merge here,
 * so it is the part that does not. A projection that cannot tell those apart will
 * say a design scales when it does not.
 *
 * <p>The frames are made a batch at a time and never held whole. At full size they
 * would arrive from storage and no master would hold them, so holding them
 * here would put a linear term in the one node whose memory is meant to be flat —
 * and the fitted law would follow it faithfully into the wrong answer.
 */
public final class RenderMaster extends JobGrpc.JobImplBase {

    /**
     * Frames per batch.
     *
     * <p>Small enough that the smallest step of a probe ladder is still many
     * batches. Batches are whole, and the worst node's disk is the peak of an
     * integer count of them, so at a few batches per renderer that rounding is a
     * real bend in the measurement — and the engine refuses a disk law over it,
     * correctly and uselessly.
     */
    static final int FRAMES_PER_BATCH = 50;

    /** Batches in flight at once, so fanning out does not mean holding the workload. */
    static final int IN_FLIGHT = 8;

    /** The shape of the data, which is this job's business and nowhere in a simulation. */
    static final int CATALOGUE = 200_000;
    static final double SKEW = 1.1;

    @Override public void load(Input in, StreamObserver<Workload> out) {
        out.onNext(Workload.newBuilder().setCount(in.getCount()).setType(in.getUnit()).build());
        out.onCompleted();
    }

    @Override public void run(Workload work, StreamObserver<Result> out) {
        var here = Losim.current();
        var renderers = here.peersServing("Thumbnailer");
        if (renderers.isEmpty()) throw new IllegalStateException("nobody serves Thumbnailer");

        var async = new ArrayList<ThumbnailerGrpc.ThumbnailerStub>();
        var blocking = new ArrayList<ThumbnailerGrpc.ThumbnailerBlockingStub>();
        for (String r : renderers) {
            var channel = here.channelTo(r);
            async.add(ThumbnailerGrpc.newStub(channel));
            blocking.add(ThumbnailerGrpc.newBlockingStub(channel));
        }

        long frames = work.getCount();
        var catalogue = new Assets(CATALOGUE, SKEW, here.seed());
        int batches = (int) ((frames + FRAMES_PER_BATCH - 1) / FRAMES_PER_BATCH);

        // Fanned out across every renderer at once, which is what makes this the
        // stretch a bigger cluster finishes sooner.
        var lost = new ConcurrentLinkedQueue<Batch>();
        var room = new Semaphore(IN_FLIGHT);
        var done = new CountDownLatch(batches);
        try {
            for (int i = 0; i < batches; i++) {
                int size = (int) Math.min(FRAMES_PER_BATCH, frames - (long) i * FRAMES_PER_BATCH);
                var assets = new StringBuilder();
                for (String asset : catalogue.frames(size)) {
                    if (assets.length() > 0) assets.append(' ');
                    assets.append(asset);
                }
                final Batch batch = Batch.newBuilder()
                        .setAssets(assets.toString()).setFrames(size).build();
                room.acquire();
                async.get(i % async.size()).withDeadlineAfter(8000, TimeUnit.MILLISECONDS)
                     .thumbnail(batch, new StreamObserver<Sizes>() {
                             @Override public void onNext(Sizes s) { }
                             @Override public void onError(Throwable t) { lost.add(batch); free(); }
                             @Override public void onCompleted() { free(); }
                             private void free() { room.release(); done.countDown(); }
                         });
            }
            done.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Whatever did not come back has to be done again somewhere else. Nobody
        // said which node died, or why: this master knows only that a batch it
        // handed out has no answer, and that is the whole of what it gets to work
        // with. It is also not bookkeeping — a survivor absorbing a dead node's
        // catalogue is why a cluster that loses a node needs more memory than one
        // that does not, and a model fitted on clean results alone under-predicts
        // by exactly that much, optimistically.
        int redone = 0;
        for (Batch batch = lost.poll(); batch != null; batch = lost.poll()) {
            redone++;
            for (String renderer : here.peersServing("Thumbnailer")) {
                try {
                    ThumbnailerGrpc.newBlockingStub(here.channelTo(renderer))
                            .withDeadlineAfter(8000, TimeUnit.MILLISECONDS).thumbnail(batch);
                    break;
                } catch (StatusRuntimeException e) {
                    // That one is gone too. Try the next; there is no third outcome.
                }
            }
        }

        // And this is one call per renderer and a merge here, so a bigger cluster
        // makes it no shorter. Nothing distinguishes the two stretches but their
        // shape — and the merge happens on the thread serving losim.Job/Run, so
        // this node is busy for it without anything having to say so.
        var merged = new TreeMap<String, Integer>();
        for (int i = 0; i < blocking.size(); i++) {
            try {
                blocking.get(i).withDeadlineAfter(8000, TimeUnit.MILLISECONDS)
                        .totals(Nothing.getDefaultInstance()).getBytesMap()
                        .forEach((asset, n) -> merged.merge(asset, n, Integer::sum));
            } catch (StatusRuntimeException e) {
                here.log(renderers.get(i) + " did not answer: " + e.getStatus().getCode());
            }
        }

        out.onNext(Result.newBuilder()
                .putAnswer("frames", String.valueOf(frames))
                .putAnswer("batches", String.valueOf(batches))
                .putAnswer("redone", String.valueOf(redone))
                .putAnswer("assets", String.valueOf(merged.size()))
                .build());
        out.onCompleted();
    }
}
