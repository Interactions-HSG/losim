package losim.verify;

import java.util.List;

/**
 * What stepping outside the simulated world makes untrustworthy.
 *
 * <p>Not "what is forbidden" — nothing here is forbidden, and the verifier that
 * raises these never fails a run (D11). Each flag names a <i>measurement</i> that
 * stops meaning what it says. A machine that reads the real clock still produces a
 * timeline; the timeline is simply no longer a model of anything, because it was
 * written in whatever the host's afternoon was doing rather than in the compressed
 * clock everything else is written in.
 *
 * <p>So the useful output is "this number is a lower bound" rather than "this code
 * is bad", attached to the machine and to the figure it undermines — which is the
 * difference between a caveat someone can act on and a warning they scroll past.
 */
public enum Flag {

    /** Real time got in, so durations no longer divide by {@code k_time}. */
    TIMELINE("timeline", "its timeline is not projectable",
             List.of("makespanRefMs")),

    /** Bytes were allocated where nothing was counting. */
    MEMORY("memory", "its memory figure is a lower bound",
           List.of("memoryMb", "allocMb")),

    /** Bytes reached a real disk, which the disk model never saw. */
    DISK("disk", "its disk figure is a lower bound",
         List.of("diskMb")),

    /** A call went out over a channel losim did not make, so nobody weighed it. */
    WIRE("wire", "its wire bytes are a lower bound",
         List.of("wireMb")),

    /**
     * Two machines share something, so no per-machine figure is only its own.
     *
     * <p>This is the one that poisons everything rather than one column: if
     * machines reach into each other, attributing anything to either of them is
     * arithmetic on a fiction.
     */
    ISOLATION("isolation", "its figures are not only its own",
              List.of("memoryMb", "allocMb", "diskMb", "wireMb", "makespanRefMs"));

    /** How the flag is written in the trace. */
    public final String key;
    /** What to say about the machine that carries it. */
    public final String consequence;
    private final List<String> undermines;

    Flag(String key, String consequence, List<String> undermines) {
        this.key = key;
        this.consequence = consequence;
        this.undermines = undermines;
    }

    /** The measured resources this flag undermines, named as the engine fits them. */
    public List<String> undermines() { return undermines; }
}
