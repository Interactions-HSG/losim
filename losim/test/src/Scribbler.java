import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import losim.api.Cost;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * A worker that spills to a real disk rather than to the modelled one.
 *
 * <p>losim accounts disk through {@code Losim.current().wroteDisk(n)} and caps it per
 * machine, which is how a machine that fills up says so. These bytes are invisible to
 * the cap, to the series and to every projection of either — and they are on the host,
 * outside the one machine everything is meant to happen on.
 */
public final class Scribbler extends WorkerBase {

    @Cost(refMs = 2)
    @Override protected Counts map(Chunk c) {
        Path spill = Path.of(System.getProperty("java.io.tmpdir"), "losim-scribbler.txt");
        try { Files.writeString(spill, c.getText()); }
        catch (IOException e) { throw new IllegalStateException(e); }
        return Counts.newBuilder().putCounts("spilled", c.getText().length()).build();
    }
}
