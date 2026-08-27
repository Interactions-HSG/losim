package losim.api;

import io.grpc.Channel;
import java.util.List;

/**
 * losim when nothing is running: a handler called straight from a unit test.
 *
 * Silent for recording, so a test need not know losim exists. Throwing for
 * state, because the alternative — an empty fleet, a zero clock — lets a test
 * assert things about a world that was never there, and pass.
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
    @Override public void records(long n)                   { }
    @Override public void wroteDisk(long bytes)             { }
    @Override public void sleep(double refMs)               { }

    @Override public String machine()                        { throw absent("machine()"); }
    @Override public Spec here()                             { throw absent("here()"); }
    @Override public List<String> peers()                    { throw absent("peers()"); }
    @Override public List<String> peersServing(String s)     { throw absent("peersServing()"); }
    @Override public double clockMs()                        { throw absent("clockMs()"); }
    @Override public Channel channelTo(String machine)       { throw absent("channelTo()"); }

    private static IllegalStateException absent(String call) {
        return new IllegalStateException(
                "no simulation is running, so Losim.current()." + call + " has no answer here. "
              + "Recording calls (reveal, log, records) are silent outside a run; state calls are "
              + "not, because a fabricated fleet would make a passing test meaningless.");
    }
}
