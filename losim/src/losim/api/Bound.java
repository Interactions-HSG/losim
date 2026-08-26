package losim.api;

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

    List<String> peers();

    List<String> peersServing(String service);

    /** Simulated milliseconds since the run began. */
    double clockMs();

    /** Records one event against this machine. */
    void event(String kind, Object... kv);

    /** Declares how many records the call in flight processed. */
    void records(long n);

    /** Takes a write, or refuses it because the disk is full. */
    void wroteDisk(long bytes);

    /**
     * Charges a region of losim's own work to this machine's ledger, and to the
     * span it happened inside — so neither the machine's reported allocation nor
     * the handler's reported duration includes it.
     */
    void charge(long bytes, long nanos);
}
