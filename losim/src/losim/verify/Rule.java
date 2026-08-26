package losim.verify;

import java.util.List;

/**
 * The catalogue: what makes a simulation lie, and what it costs.
 *
 * <p>This list is the feature. It is short on purpose, and everything on it earns
 * its place the same way — by naming something that yields a <b>wrong number</b>
 * rather than a broken run. Code that reads the real clock still works; it simply
 * reports the host's afternoon as though it were the simulated world, and the two
 * differ by {@code k_time}.
 *
 * <p>What is <i>not</i> here matters as much. Nothing is flagged for determinism's
 * sake — unseeded {@code Random}, {@code Math.random} and identity-hash iteration
 * order are all fine, because runs are not reproducible anyway (D1) and a sweep of
 * seeds is the answer to that. Raw threads are not banned either: real concurrency
 * inside a machine is a feature. Work outside the machine's own pool is merely
 * attributed to nobody, and that is what gets said.
 */
public enum Rule {

    /**
     * The real clock, read from inside the simulated world.
     *
     * <p>Every duration losim knows is reference-machine time divided by
     * {@code k_time} (D3). A handler that reads {@code System.nanoTime} gets the
     * host's afternoon instead, mixes the two scales in one number, and reports
     * whichever the code happened to use.
     */
    REAL_CLOCK(Flag.TIMELINE, "reads the real clock", List.of(
            "java.lang.System#nanoTime",
            "java.lang.System#currentTimeMillis",
            "java.time.Instant#now",
            "java.time.LocalDate#now",
            "java.time.LocalDateTime#now",
            "java.time.LocalTime#now",
            "java.time.OffsetDateTime#now",
            "java.time.OffsetTime#now",
            "java.time.ZonedDateTime#now",
            "java.time.Year#now",
            "java.time.YearMonth#now",
            "java.time.Clock#system*")),

    /**
     * Real time, slept.
     *
     * <p>The same lie from the other end, and it is about the <i>unit</i> rather
     * than about waiting. A hand-rolled backoff that sleeps 100 ms sleeps 100 ms
     * whatever {@code k_time} is, so at a compression of forty it is four thousand
     * reference milliseconds of the simulated world — the one duration in the run
     * that does not move when the compression does.
     *
     * <p>Waiting is a perfectly ordinary thing for a distributed program to do, and
     * {@code Losim.current().sleep(refMs)} is how to write it: reference time, like
     * every other duration, divided by {@code k_time} before it is spent. It is not
     * {@code @Takes}, which is an annotation and so cannot express a backoff that
     * grows with the attempt — and which stretches on a degraded machine, where a
     * wait does not.
     *
     * <p>Blocking on real work — a latch, a future, {@code Object.wait}, a bare
     * {@code LockSupport.park()} — is not this and is not flagged: waiting for
     * something that is happening is what a distributed program is mostly made of,
     * and its length is set by that something's own progress rather than written
     * down. A <i>timeout</i> on such a wait is a real duration that k_time does not
     * touch, but it is only spent when the wait fails, and a static check cannot see
     * which run that is — so the timed forms of those are left alone too. The two
     * {@code LockSupport} entries below are the exception on frequency alone: they
     * are rare enough in application code that reading them as a hand-rolled sleep
     * is the likelier guess.
     */
    REAL_SLEEP(Flag.TIMELINE, "sleeps in host milliseconds, which k_time does not touch,"
            + " rather than in reference time", List.of(
            "java.lang.Thread#sleep",
            "java.util.concurrent.TimeUnit#sleep",
            "java.util.concurrent.locks.LockSupport#parkNanos",
            "java.util.concurrent.locks.LockSupport#parkUntil")),

    /**
     * Work on a thread the machine did not create.
     *
     * <p>A machine is its pool: every thread in it belongs to exactly one machine,
     * which is how allocation is attributed at all (D12) and how the vCPU model
     * means anything (D1). A thread outside it is summed by nobody and contends for
     * nobody's cores, so the machine reads cheaper and less busy than it is.
     *
     * <p>Virtual threads are here rather than in a rule of their own, because the
     * failure is the same one: what makes the figure wrong is that the thread is not
     * the machine's, exactly as for a platform thread the handler spawned. (The −1
     * that {@code getThreadAllocatedBytes} returns for a virtual thread is why
     * <i>losim's own pool</i> is platform threads — it is not what goes wrong here.)
     * One difference is worth knowing and is not worth a second rule: switching to a
     * platform thread would not fix this either.
     *
     * <p>Nor is the remedy "use fewer threads". Real concurrency inside a machine is
     * a feature (D1); in losim, work is spread by <b>calling more machines</b>, and a
     * machine's own concurrency comes from serving several calls at once on its pool.
     */
    UNATTRIBUTED_THREAD(Flag.MEMORY, "runs work on a thread the machine did not create,"
            + " so its allocation and its CPU are charged to nobody", List.of(
            "java.lang.Thread#start",
            "java.lang.Thread#ofVirtual",
            "java.lang.Thread#startVirtualThread",
            "java.util.concurrent.Executors#*",
            "java.util.concurrent.ForkJoinPool#*",
            "java.util.concurrent.CompletableFuture#supplyAsync",
            "java.util.concurrent.CompletableFuture#runAsync",
            "*#parallelStream",
            "java.util.stream.*#parallel")),

    /**
     * A real file, which the disk model never saw.
     *
     * <p>losim accounts disk through {@code Losim.current().wroteDisk(n)} and caps
     * it per machine, so a machine that fills its disk says so. Bytes written past
     * that are invisible to the cap and to every projection of it — and they are
     * also written to the host, outside the one-machine boundary.
     */
    FILE_IO(Flag.DISK, "writes to a real disk, which the disk model never sees", List.of(
            "java.nio.file.Files#*",
            "java.io.FileOutputStream#*",
            "java.io.FileInputStream#*",
            "java.io.FileWriter#*",
            "java.io.FileReader#*",
            "java.io.RandomAccessFile#*",
            "java.io.File#delete",
            "java.io.File#mkdir",
            "java.io.File#mkdirs",
            "java.io.File#createNewFile",
            "java.io.File#renameTo")),

    /**
     * Something outside this JVM.
     *
     * <p>"On one machine" is the boundary the whole design rests on: nothing leaves
     * the JVM, so anything that does is neither simulated, nor measured, nor
     * reproducible on the next host.
     */
    OUTSIDE_THE_JVM(Flag.ISOLATION, "reaches outside the JVM, where nothing is simulated",
            List.of(
            "java.net.Socket#*",
            "java.net.ServerSocket#*",
            "java.net.DatagramSocket#*",
            "java.net.URL#openStream",
            "java.net.URL#openConnection",
            "java.net.http.*#*",
            "java.nio.channels.SocketChannel#*",
            "java.nio.channels.ServerSocketChannel#*",
            "java.lang.ProcessBuilder#*",
            "java.lang.Runtime#exec")),

    /**
     * A channel or a server the fleet did not make.
     *
     * <p>losim is on both sides of every call as gRPC's own interceptors: that is
     * where latency, loss, partitions, cost, spans and byte counts come from. A
     * channel built by hand has none of them attached, so the call happens and
     * nothing in the trace records that it did.
     */
    OWN_CHANNEL(Flag.WIRE, "builds its own channel or server, which no interceptor is"
            + " attached to", List.of(
            "io.grpc.ManagedChannelBuilder#*",
            "io.grpc.ServerBuilder#*",
            "io.grpc.Grpc#newChannelBuilder*",
            "io.grpc.Grpc#newServerBuilder*",
            "io.grpc.inprocess.*#*",
            "io.grpc.netty.*#*",
            "io.grpc.okhttp.*#*")),

    /**
     * State every machine shares.
     *
     * <p>A static field is one field for the whole fleet, so a fleet of eight
     * "machines" holding a static map holds one map. It is a lie about isolation
     * rather than about repeatability, and it is the one flag that undermines every
     * per-machine figure at once: whatever is in there was allocated by whichever
     * machine happened to touch it first, and retained by all of them.
     *
     * <p>Constants are not this. A {@code static final} primitive, {@code String}
     * or enum constant is shared and immutable, which is what constants are for.
     */
    SHARED_STATE(Flag.ISOLATION, "holds mutable state in a static field, which is one"
            + " field for the whole fleet rather than one per machine", List.of()),

    /**
     * One machine, reaching into another.
     *
     * <p>The sharpest form of the above, and worth saying separately because it is
     * not a measurement problem but a modelling one: gRPC is the only way machines
     * talk, and there is no second path. A static handle on another machine's
     * service is a call that crosses no network, waits out no latency, survives a
     * partition, and keeps working after the machine at the other end is killed.
     */
    MACHINES_TOUCHING(Flag.ISOLATION, "reaches another machine's service through a static"
            + " field rather than over gRPC — which crosses no network, and survives"
            + " a partition and a kill that a real call would not", List.of());

    public final Flag flag;
    /** What the code did, phrased to follow a machine name: "w3 …". */
    public final String because;
    private final List<String> calls;

    Rule(Flag flag, String because, List<String> calls) {
        this.flag = flag;
        this.because = because;
        this.calls = calls;
    }

    /**
     * The rule a call trips, or null.
     *
     * <p>First match wins, so the order of the constants above is the order of
     * specificity: a virtual-thread executor is a virtual-thread finding, not
     * merely an unattributed one.
     */
    public static Rule forCall(String owner, String member) {
        for (Rule r : values())
            for (String pattern : r.calls)
                if (matches(pattern, owner, member)) return r;
        return null;
    }

    private static boolean matches(String pattern, String owner, String member) {
        int hash = pattern.indexOf('#');
        return part(pattern.substring(0, hash), owner)
            && part(pattern.substring(hash + 1), member);
    }

    private static boolean part(String pattern, String actual) {
        if (pattern.equals("*")) return true;
        if (pattern.endsWith(".*")) return actual.startsWith(pattern.substring(0, pattern.length() - 1));
        if (pattern.endsWith("*")) return actual.startsWith(pattern.substring(0, pattern.length() - 1));
        return pattern.equals(actual);
    }
}
