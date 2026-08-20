package losim.api;

/**
 * A property that must hold. Predicates are code, so the scenario only
 * references them by class name — the config itself stays declarative data.
 */
public interface Invariant {
    /** Throw {@link Violation} to fail. */
    void check(RunResult run);

    final class Violation extends RuntimeException {
        public Violation(String message) { super(message); }
    }
}
