import io.grpc.stub.StreamObserver;
import losim.api.Losim;
import thumbs.pb.Blob;
import thumbs.pb.Receipt;
import thumbs.pb.StoreGrpc;

/**
 * Somewhere to put the thumbnails, so that a design has a tier the renderers
 * depend on.
 *
 * <p>It exists to be somewhere else. Place it beside the renderers and its calls
 * cost the same as any other; place it in a second zone and every write pays the
 * crossing, which is the one difference between two of the worked examples.
 */
public final class BlobStore extends StoreGrpc.StoreImplBase {

    @Override public void put(Blob blob, StreamObserver<Receipt> out) {
        var here = Losim.current();
        here.wroteDisk(blob.getBytes());
        here.units(1);
        out.onNext(Receipt.newBuilder().setKey(blob.getKey()).setStored(true).build());
        out.onCompleted();
    }
}
