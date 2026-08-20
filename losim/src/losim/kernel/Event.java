package losim.kernel;

/** A scheduled action. Ordered by (time, seq) so ties break deterministically. */
public record Event(long time, long seq, String label, Runnable action) implements Comparable<Event> {
    @Override public int compareTo(Event o) {
        int c = Long.compare(time, o.time);
        return c != 0 ? c : Long.compare(seq, o.seq);
    }
}
