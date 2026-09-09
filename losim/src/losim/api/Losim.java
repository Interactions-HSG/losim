package losim.api;

/**
 * The losim type a handler uses to access the current context.
 *
 * <pre>{@code
 * @Override protected Pairs map(Chunk req) {
 *     var pairs = count(req.getText());
 *     Losim.current().reveal("emitted", pairs.getPairsCount());
 *     return pairs;
 * }
 * }</pre>
 *
 * The type need not appear in a service signature, so the same handler can be
 * called from a unit test without a running simulation.
 */
public final class Losim {
    private Losim() {}

    private static final LosimCtx PRESENT = new Present();
    private static final LosimCtx ABSENT  = new Absent();

    /**
     * Returns the context for the current thread.
     *
     * <p>The method returns one of two singletons and allocates nothing. Repeated
     * calls therefore do not add objects to the program's allocation measurement.
     */
    public static LosimCtx current() {
        return Ambient.MACHINE.get() == null ? ABSENT : PRESENT;
    }
}
