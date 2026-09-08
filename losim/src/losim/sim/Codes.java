package losim.sim;

import java.util.List;

/**
 * Every gRPC status code, by name, without gRPC on the classpath.
 *
 * <p>The loader has to refuse {@code status: UNAVALABLE} on the line it is
 * written on, and it cannot ask {@code io.grpc.Status.Code} to do it: the
 * console's server runs with {@code losim.jar} alone — the gRPC jars belong to
 * the assignment and are only ever on a simulation's classpath, in the JVM the
 * simulation forks. Reading that enum from here is a {@code NoClassDefFoundError}
 * the moment somebody saves a simulation from the console, which is exactly the
 * shape of failure {@link losim.cli.Palette} is written the way it is to avoid.
 *
 * <p><b>A second copy of somebody else's enum is a thing that drifts</b>, so it
 * is held against the real one by a test rather than by hope. These are also the
 * least likely names in the ecosystem to change: they are the wire protocol, one
 * integer each, fixed by the gRPC specification since 1.0 — a new code would be a
 * new protocol.
 *
 * <p>{@code OK} is not here. It is what a call that worked returns, so a failure
 * cannot be one, and leaving it out is what lets the refusal below list exactly
 * the codes somebody could have meant.
 */
public final class Codes {

    private Codes() {}

    /** In {@code io.grpc.Status.Code}'s own order, which is the protocol's. */
    public static final List<String> ALL = List.of(
            "CANCELLED", "UNKNOWN", "INVALID_ARGUMENT", "DEADLINE_EXCEEDED", "NOT_FOUND",
            "ALREADY_EXISTS", "PERMISSION_DENIED", "RESOURCE_EXHAUSTED", "FAILED_PRECONDITION",
            "ABORTED", "OUT_OF_RANGE", "UNIMPLEMENTED", "INTERNAL", "UNAVAILABLE",
            "DATA_LOSS", "UNAUTHENTICATED");

    public static boolean known(String name) { return ALL.contains(name); }

    /** For a refusal to list, so nobody has to go and find them. */
    public static String listed() { return String.join(", ", ALL); }
}
