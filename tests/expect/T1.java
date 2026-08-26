import lab.pb.Chunk;
import lab.pb.Counts;
import losim.api.Losim;

/**
 * t1-handler-alone — a gRPC service's handler, called straight from a test.
 *
 * <p><b>Catches:</b> losim leaking into a signature, and an absent context inventing
 * state that makes a green test meaningless.
 *
 * <p>No fleet, no scenario, no interceptor and no clock: a class, constructed, with
 * a method called on it. Set a breakpoint in {@code map} and stepping into it stops
 * nothing else, because nothing else is running. If this case ever stops compiling,
 * a losim type has appeared in a signature and the shape has been lost.
 */
public final class T1 {
    public static void main(String[] args) {
        var e = Expect.bare("t1-handler-alone");

        Counts got = new Mapper().map(Chunk.newBuilder().setText("a b a").setLines(1).build());

        e.check(got.getCountsOrDefault("a", 0) == 2 && got.getCountsOrDefault("b", 0) == 1,
                "the handler returned the right protobuf, called as an ordinary method");
        e.check(!Losim.current().isRunning(),
                "and it knows nothing is running, rather than being told otherwise");

        boolean silent = true;
        try {
            Losim.current().reveal("emitted", 2);
            Losim.current().records(1);
            Losim.current().log("counted");
            Losim.current().sleep(5_000);
        } catch (RuntimeException x) { silent = false; }
        e.check(silent, "recording is silent outside a run — including a declared wait, which "
                + "returns at once rather than making a test suite slower for nothing");

        String why = null;
        try { Losim.current().peers(); } catch (IllegalStateException x) { why = x.getMessage(); }
        e.check(why != null && why.contains("no simulation is running"),
                "but asking about the fleet throws, because a fabricated one would let this "
                + "test assert things about a world that was never there — and pass");

        String dialled = null;
        try { Losim.current().channelTo("srv"); } catch (IllegalStateException x) { dialled = x.getMessage(); }
        e.check(dialled != null, "and so does asking for a channel to a machine that does not exist");

        e.done();
    }
}
