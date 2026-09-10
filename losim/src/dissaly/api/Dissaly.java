package dissaly.api;

/**
 * The losim type a handler uses to access the current context.
 *
 * <pre>{@code
 * @Override protected Pairs map(Chunk req) {
 *     var pairs = count(req.getText());
 *     Dissaly.current().reveal("emitted", pairs.getPairsCount());
 *     return pairs;
 * }
 * }</pre>
 *
 * The type need not appear in a service signature, so the same handler can be
 * called from a unit test without a running simulation.
 */
public final class Dissaly {
    private Dissaly() {}

    private static final DissalyCtx PRESENT = new Present();
    private static final DissalyCtx ABSENT  = new Absent();

    /**
     * Returns the context for the current thread.
     *
     * <p>The method returns one of two singletons and allocates nothing. Repeated
     * calls therefore do not add objects to the program's allocation measurement.
     */
    public static DissalyCtx current() {
        return Ambient.MACHINE.get() == null ? ABSENT : PRESENT;
    }
}
