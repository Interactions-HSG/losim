import java.io.IOException;
import java.net.Socket;
import losim.api.Takes;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * A worker that opens a socket.
 *
 * <p>"On one machine" is the boundary the whole design rests on: nothing leaves the
 * JVM, so anything that does is neither simulated, nor measured, nor there at all on
 * the next host. A fleet member with a socket is not a machine in this cluster.
 */
public final class Caller extends WorkerBase {

    @Takes(refMs = 2)
    @Override protected Counts map(Chunk c) {
        try (Socket socket = new Socket()) {
            return Counts.newBuilder().putCounts("bound", socket.isBound() ? 1 : 0).build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
