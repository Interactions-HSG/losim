import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import losim.api.Losim;
import thumbs.pb.Split;
import thumbs.pb.Blob;
import thumbs.pb.Format;
import thumbs.pb.Nothing;
import thumbs.pb.Sizes;
import thumbs.pb.StoreGrpc;
import thumbs.pb.ThumbnailerGrpc;

/**
 * A renderer that keeps a thumbnail for every asset it has ever seen.
 *
 * <p>Three resources, growing at three different rates, which is why this service
 * looks the way it does rather than simpler:
 *
 * <ul>
 *   <li><b>memory</b> follows the <i>catalogue</i> — one retained thumbnail per
 *       distinct asset — and the catalogue saturates, because popular assets come
 *       back and rare ones do not;</li>
 *   <li><b>disk</b> follows the <i>volume</i>, linearly, because every split is
 *       written out whether or not its assets were new;</li>
 *   <li><b>wire bytes</b> follow the split count, with a per-call constant that is
 *       proportionally huge while the workload is small.</li>
 * </ul>
 *
 * <p>Nothing here declares a size. What this node holds is whatever the heap walk
 * finds it holding, so a node too small for its catalogue fails for the reason a
 * real one would rather than by an accounting fiction.
 *
 * <p>An ordinary gRPC service from an ordinary {@code .proto}. The two losim calls
 * are in the body and not in a signature, so this same class can be constructed
 * and called from a plain unit test with nothing simulating anything.
 */
public final class Shrinker extends ThumbnailerGrpc.ThumbnailerImplBase {

    /**
     * Bytes of decoded thumbnail kept per distinct asset.
     *
     * <p>Small, so that the nodes can be small too — the point of the
     * out-of-memory example is a node that cannot hold its catalogue, and a
     * realistic thumbnail would need a node nobody wants to wait for.
     */
    static final int RETAINED_PER_ASSET = 4096;

    /** Bytes written out per frame, whether or not its asset was already known. */
    static final int WRITTEN_PER_FRAME = 900;

    private final Map<String, Integer> seen = new ConcurrentHashMap<>();
    private final Map<String, byte[]> kept = new ConcurrentHashMap<>();

    @Override public void thumbnail(Split split, StreamObserver<Sizes> out) {
        try {
            var here = Losim.current();
            var mine = new HashMap<String, Integer>();
            for (String asset : split.getAssets().split(" ")) {
                if (asset.isEmpty()) continue;
                mine.merge(asset, 1, Integer::sum);
                seen.merge(asset, 1, Integer::sum);
                kept.computeIfAbsent(asset, k -> new byte[RETAINED_PER_ASSET]);
            }

            here.units(split.getFrames());
            // Every split is written out, new assets or not, so disk follows the
            // volume while memory follows the catalogue.
            here.wroteDisk((long) split.getFrames() * WRITTEN_PER_FRAME);
            // How the engine learns what this node's memory is really a function of.
            here.reveal("distinctAssets", kept.size());

            keepInStore(split);

            var answer = Sizes.newBuilder().setFormat(Format.WEBP);
            mine.forEach((asset, n) -> answer.putBytes(asset, n * RETAINED_PER_ASSET));
            out.onNext(answer.build());
            out.onCompleted();
        } catch (RuntimeException e) {
            out.onError(Status.INTERNAL.withDescription(String.valueOf(e.getMessage()))
                    .asRuntimeException());
        }
    }

    @Override public void totals(Nothing ignored, StreamObserver<Sizes> out) {
        var answer = Sizes.newBuilder().setFormat(Format.WEBP);
        seen.forEach((asset, n) -> answer.putBytes(asset, n * RETAINED_PER_ASSET));
        out.onNext(answer.build());
        out.onCompleted();
    }

    /**
     * A second hop, when there is a peer to make it to.
     *
     * <p>The peer is found by what it offers, never by hostname, and the channel
     * comes from losim rather than being built here. A simulation that places no
     * store has no second hop, and the same class is a one-tier design there — the
     * topology decides, not the code.
     */
    private void keepInStore(Split split) {
        var here = Losim.current();
        var stores = here.peersServing("Store");
        if (stores.isEmpty()) return;
        String key = here.node() + "/" + split.hashCode();
        try {
            StoreGrpc.newBlockingStub(here.channelTo(stores.get(split.getFrames() % stores.size())))
                    .withDeadlineAfter(4000, TimeUnit.MILLISECONDS)
                    .put(Blob.newBuilder().setKey(key)
                            .setBytes(split.getFrames() * WRITTEN_PER_FRAME).build());
        } catch (RuntimeException e) {
            // The store did not answer. The thumbnails are still correct; what is
            // lost is the copy, and the caller is told nothing, because a renderer
            // that failed a split over a cold store would be a worse design.
            here.reveal("storeMissed", true);
        }
    }
}
