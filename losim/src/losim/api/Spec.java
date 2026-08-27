package losim.api;

/**
 * What the machine serving this call is made of.
 *
 * <p>Local knowledge, and that is why it is here rather than on {@link Cluster}.
 * A real process can read its own limits — {@code Runtime.availableProcessors()},
 * its cgroup's memory ceiling, the size of the volume it is writing to, the
 * availability zone in its instance metadata. None of that requires a network.
 *
 * <p>What a real process <i>cannot</i> do is read someone else's, and losim will
 * not either. An orchestrator that wants to send small work to small machines has
 * to <b>ask</b> them, over gRPC, like a scheduler does — which is the whole reason
 * a resource manager has a registration call. Handing the coordinator a table of
 * everyone's capacity for free would teach that placement is a lookup, when the
 * interesting part is that the table is stale the moment it is built.
 *
 * <p>The caps are the <i>scaled</i> caps: what this machine is allowed on this
 * run, not what the instance type says at full scale. A job that places work by
 * comparing them is therefore placing it the same way at either scale, which is
 * the property the whole scale model exists to preserve.
 */
public record Spec(String machine, String instance, String zone, int vcpu,
                   double memoryCapMb, double diskCapMb) {

    /**
     * How much work this machine can be given relative to a two-core one.
     *
     * <p>Deliberately crude, and deliberately not a score losim computes for you:
     * placing work well is the exercise, and a ready-made ranking is the exercise
     * already done. This is the obvious first thing to try, which is what makes it
     * a useful thing to beat.
     */
    public double cores() { return vcpu / 2.0; }
}
