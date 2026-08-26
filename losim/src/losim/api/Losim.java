package losim.api;

/**
 * The one losim type a handler mentions.
 *
 * <pre>{@code
 * @Cost(refMs = 2)
 * @Override protected Pairs map(Chunk req) {
 *     var pairs = count(req.getText());
 *     Losim.current().reveal("emitted", pairs.getPairsCount());
 *     return pairs;
 * }
 * }</pre>
 *
 * Nothing here appears in a signature, so the same method can be constructed and
 * called from a plain unit test with no simulation running at all.
 */
public final class Losim {
    private Losim() {}

    private static final LosimCtx PRESENT = new Present();
    private static final LosimCtx ABSENT  = new Absent();

    /**
     * The context for the call on this thread.
     *
     * <p>Returns one of two singletons, so reaching for it allocates nothing.
     * That matters: a handler calling this a thousand times must not thereby
     * allocate a thousand objects against the machine whose allocation is being
     * fitted (D13).
     */
    public static LosimCtx current() {
        return Ambient.MACHINE.get() == null ? ABSENT : PRESENT;
    }
}
