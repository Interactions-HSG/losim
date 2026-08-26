import losim.api.Cost;
import losim.t.Chunk;
import losim.t.Counts;

/**
 * A worker that is itself a thread, and starts itself.
 *
 * <p>Which is why the declaration is read and not only the call sites: {@code start()}
 * here compiles to {@code Spawner$Split.start}, a method on a lab class, and matches
 * nothing at all. Only "extends Thread" gives it away — and the work still lands on a
 * thread this machine did not create, charged to nobody.
 */
public final class Spawner extends WorkerBase {

    private static final class Split extends Thread {
        private final String text;
        private volatile int words;
        Split(String text) { this.text = text; }
        @Override public void run() { words = text.split(" ").length; }
    }

    @Cost(refMs = 2)
    @Override protected Counts map(Chunk c) {
        var split = new Split(c.getText());
        split.start();
        try { split.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return Counts.newBuilder().putCounts("words", split.words).build();
    }
}
