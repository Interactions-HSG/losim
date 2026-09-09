package losim.api;

/**
 * The fixed capacity of the node serving the current call.
 *
 * <p>This is local knowledge, so it belongs here rather than on {@link Cluster}.
 * A real process can read its own limits with {@code Runtime.availableProcessors()},
 * its cgroup's memory ceiling, the size of the volume it is writing to, the
 * availability zone in its instance metadata. None of that requires a network.
 *
 * <p>A real process cannot read another node's limits. An orchestrator that needs
 * that information asks the node over gRPC, as a scheduler would. A master
 * therefore receives current capacity through a registration call rather than a
 * shared table.
 *
 * <p>The caps are the <i>scaled</i> caps: what this node may use on this
 * run, not what the instance type says at full scale. A job that places work by
 * comparing them is therefore placing it the same way at either scale, which is
 * the property the whole scale model exists to preserve.
 */
public record Spec(String node, String instance, String zone, int vcpu,
                   double memoryCapMb, double diskCapMb) {

    /**
     * Relative capacity compared with a two-core node.
     *
     * <p>This is a capacity hint, not a placement score. It is useful for comparing
     * nodes with different vCPU counts.
     */
    public double cores() { return vcpu / 2.0; }
}
