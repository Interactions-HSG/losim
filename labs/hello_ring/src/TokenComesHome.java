import losim.api.Invariant;
import losim.api.RunResult;

/** The token must complete the ring. A predicate is code; the scenario names it. */
public final class TokenComesHome implements Invariant {
    @Override public void check(RunResult run) {
        if (run.output() == null)
            throw new Violation("the token never came home (no VM called ctx.done)");
    }
}
