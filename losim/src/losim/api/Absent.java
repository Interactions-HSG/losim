package losim.api;

import io.grpc.Channel;
import java.util.List;

/**
 * The context used when a handler is called directly from a unit test.
 *
 * Recording calls are silent, so a unit test need not depend on losim. State calls
 * throw because an invented cluster or clock would make the test misleading.
 */
final class Absent implements LosimCtx {

    @Override public boolean isRunning() { return false; }

    @Override public void reveal(String key, int value)     { }
    @Override public void reveal(String key, long value)    { }
    @Override public void reveal(String key, double value)  { }
    @Override public void reveal(String key, boolean value) { }
    @Override public void reveal(String key, String value)  { }
    @Override public void reveal(String key, Object value)  { }
    @Override public void log(String message)               { }
    @Override public void units(long n)                   { }
    @Override public void wroteDisk(long bytes)             { }
    @Override public void alsoHolds(Object held)            { }
    @Override public void sleep(double refMs)               { }

    @Override public String node()                           { throw absent("node()"); }
    @Override public long seed()                             { throw absent("seed()"); }
    @Override public java.util.concurrent.ConcurrentMap<String, Object> local() {
        throw absent("local()");
    }
    @Override public Spec here()                             { throw absent("here()"); }
    @Override public List<String> peers()                    { throw absent("peers()"); }
    @Override public List<String> peersServing(String s)     { throw absent("peersServing()"); }
    @Override public double clockMs()                        { throw absent("clockMs()"); }
    @Override public Channel channelTo(String machine)       { throw absent("channelTo()"); }

    private static IllegalStateException absent(String call) {
        return new IllegalStateException(
                "no simulation is running, so Losim.current()." + call + " has no answer here. "
              + "Recording calls (reveal, log, units) are silent outside a run; state calls "
              + "throw, because a fabricated cluster would make a passing test misleading.");
    }
}
