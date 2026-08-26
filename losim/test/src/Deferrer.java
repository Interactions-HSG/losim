import java.util.function.LongSupplier;
import losim.api.Takes;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * The same wrong clock as {@link Peeker}, reached by a method reference.
 *
 * <p>Which is why the verifier reads the bootstrap methods and not only the code:
 * {@code System::nanoTime} compiles to an argument of an {@code invokedynamic} and
 * appears in no instruction anywhere. Reading the instructions alone, this class looks
 * spotless.
 */
public final class Deferrer extends WorkerBase {

    private static final LongSupplier WHEN = System::nanoTime;

    @Takes(refMs = 2)
    @Override protected Counts map(Chunk c) {
        return Counts.newBuilder().putCounts(c.getText().trim(), (int) (WHEN.getAsLong() % 97)).build();
    }
}
