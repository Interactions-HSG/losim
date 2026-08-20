import losim.api.Invariant;
import losim.api.RunResult;

/** The job is only done when every shard is. */
public final class EveryShardDone implements Invariant {
    @Override public void check(RunResult run) {
        if (run.output() == null) throw new Violation("the job never finished");
        String out = String.valueOf(run.output());
        if (out.contains("lost ")) throw new Violation(out);
    }
}
