package losim.api;

import io.grpc.Channel;
import java.util.List;

/**
 * The machine a call is being served by, as the facade sees it.
 *
 * This is the seam between what a program may say to losim and what losim does
 * about it. {@code losim.api} names it; {@code losim.runtime} implements it. The
 * facade therefore depends on no runtime type, which is what lets a handler be
 * compiled and unit-tested against the API alone.
 *
 * <p>Not part of the surface a student writes against.
 */
public interface Bound {

    String name();

    /**
     * What this machine is made of — cached, not measured: every field is final
     * on the machine and known before it boots.
     */
    Spec here();

    List<String> peers();

    List<String> peersServing(String service);

    /** Simulated milliseconds since the run began. */
    double clockMs();

    /**
     * A channel to a peer, made once and owned by the machine.
     *
     * <p>Cached rather than built per call, because a handler that had to manage a
     * channel's lifetime would have to be told about lifetimes at all.
     */
    Channel dial(String peer);

    /** Records one event against this machine. */
    void event(String kind, Object... kv);

    /** Declares how many records the call in flight processed. */
    void records(long n);

    /** Takes a write, or refuses it because the disk is full. */
    void wroteDisk(long bytes);

    /**
     * Spends a declared duration against the compressed clock.
     *
     * <p>The program's own time, not losim's, so the caller must leave this
     * outside whatever region it is metering.
     */
    void sleep(double refMs);

    /**
     * Charges a region of losim's own work to this machine's ledger, and to the
     * span it happened inside — so neither the machine's reported allocation nor
     * the handler's reported duration includes it.
     */
    void charge(long bytes, long nanos);
}
