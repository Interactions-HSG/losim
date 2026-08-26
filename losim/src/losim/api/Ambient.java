package losim.api;

import io.grpc.Context;

/**
 * Where the running machine is kept.
 *
 * gRPC's own {@link Context} is the carrier: the server interceptor attaches the
 * machine with {@code Contexts.interceptCall}, and it is then ambient for
 * anything the handler does on that thread. Nothing is passed through a
 * signature, which is what keeps losim out of the shape a student writes (D2).
 *
 * <p>The context does <b>not</b> follow work onto a thread the machine did not
 * create. Either the machine's executor is wrapped once with
 * {@code Context.currentContextExecutor}, or that work loses its machine
 * identity — and is flagged rather than silently attributed to nobody (D11).
 */
public final class Ambient {
    private Ambient() {}

    /** The machine serving the call on this thread, or null outside a run. */
    public static final Context.Key<Bound> MACHINE = Context.key("losim.machine");
}
