import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import dissaly.runtime.Simulate;
import dissaly.sim.Loader;
import dissaly.sim.Yaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What goes wrong with one rpc, on one node and not its peers.
 *
 * <p>This is the case a design handles worst and the whole reason {@code
 * failures:} is written inside {@code runs:} rather than beside the cluster: a
 * node that is dead is a node every design already copes with, and a node that
 * answers three calls in four is the one that quietly ruins an afternoon.
 *
 * <p>Each kind is observed through the <b>caller's own status</b>, which is the
 * only place any of them shows up without reading the trace — and is what a
 * student's Java has to handle.
 */
class RpcFailuresTest {

    /** Three workers, and whatever is wrong with the third one. */
    private static String system(String w2) {
        return """
            seed: 4
            nodes:
              master:
                instance: m5.large
                zone: z
                runs: { dissaly.Job: dissaly/test/src/Prober.java }
              w0:
                instance: c5.large
                zone: z
                runs: { Worker: dissaly/test/src/Counter.java }
              w1:
                instance: c5.large
                zone: z
                runs: { Worker: dissaly/test/src/Counter.java }
              w2:
                instance: c5.large
                zone: z
            %s
            input:
              unit:  line
              count: 3
            simulatedDuration:
              dissaly/test/src/Counter.java: { Map: { fixed: 5 refMs } }
            """.formatted(w2);
    }

    /** The plain entry, for the case where nothing is wrong anywhere. */
    private static final String FINE = """
                runs: { Worker: dissaly/test/src/Counter.java }
            """;

    /** One service on one node, with one rpc misbehaving every call. */
    private static String failing(String what) {
        return """
                runs:
                  Worker:
                    file: dissaly/test/src/Counter.java
                    failures:
                      Map:
                        - { %s, per: 1 calls }
            """.formatted(what);
    }

    /** What every peer answered, keyed by the peer. */
    private static Map<String, String> answers(String yaml) throws Exception {
        var result = Simulate.of(Loader.of(Yaml.parse("rpc.yaml", yaml)),
                RpcFailuresTest.class.getClassLoader());
        assertTrue(result.completed(), () -> "the simulation did not finish: " + result.failure());
        var done = result.telemetry().events().stream()
                .filter(e -> e.kind().equals("done")).findFirst().orElseThrow();
        Object value = done.detail().get("value");
        assertInstanceOf(Map.class, value);
        Object answer = ((Map<?, ?>) value).get("answer");
        assertInstanceOf(Map.class, answer);
        var out = new java.util.LinkedHashMap<String, String>();
        for (var e : ((Map<?, ?>) answer).entrySet()) {
            out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return out;
    }

    @Test
    @DisplayName("with nothing wrong, every peer answers — which is what the rest is measured against")
    void theGoodAfternoon() throws Exception {
        var said = answers(system(FINE));
        assertEquals("3", said.get("peers"));
        assertEquals(Map.of("w0", "OK", "w1", "OK", "w2", "OK"),
                Map.of("w0", said.get("w0"), "w1", said.get("w1"), "w2", said.get("w2")));
    }

    @Test
    @DisplayName("status: the caller gets that code, and only from the node it was written on")
    void statusReachesTheCallerAndNobodyElse() throws Exception {
        var said = answers(system(failing("status: RESOURCE_EXHAUSTED")));
        assertEquals("RESOURCE_EXHAUSTED", said.get("w2"),
                "the handler is never reached; the caller gets the code the file named");
        // The half that makes it a *replica* rather than an outage. A failure
        // written on one node and read as the service's would pass the line
        // above and be a completely different simulation.
        assertEquals("OK", said.get("w0"));
        assertEquals("OK", said.get("w1"));
    }

    @Test
    @DisplayName("drop: the request never arrives, so the caller runs out of time")
    void dropIsIndistinguishableFromATimeout() throws Exception {
        var said = answers(system(failing("drop: true")));
        assertEquals("DEADLINE_EXCEEDED", said.get("w2"),
                "a dropped request reaches no handler, so nothing but the caller's own"
                + " clock can end that call — which is what makes a deadline the only defence");
        assertEquals("OK", said.get("w0"));
    }

    @Test
    @DisplayName("slow: the call is answered, late, and only that rpc on that node")
    void slowAnswersLate() throws Exception {
        // 5 refMs declared, ten times that here. Long enough to see and well
        // inside the caller's 200 refMs deadline, so a `slow` that had turned
        // into a timeout would fail as the wrong code rather than pass quietly.
        var said = answers(system(failing("slow: 10")));
        assertEquals("OK", said.get("w2"), "it is answered; it is late");

        var quick = handlerRefMs(system(FINE));
        var late = handlerRefMs(system(failing("slow: 10")));
        assertTrue(late > quick * 3,
                () -> "a slow rpc should take visibly longer: " + quick + " -> " + late);
    }

    @Test
    @DisplayName("an rpc failure is a moment in the trace, so a reader can step to it")
    void itIsInTheTrace() throws Exception {
        var result = Simulate.of(
                Loader.of(Yaml.parse("rpc.yaml", system(failing("status: UNAVAILABLE")))),
                RpcFailuresTest.class.getClassLoader());
        var said = result.telemetry().events().stream()
                .filter(e -> e.kind().equals("rpc_failure")).toList();
        assertFalse(said.isEmpty(), "nothing in the trace says the rpc was failed on purpose");
        assertEquals("w2", said.get(0).vm(), "and it happened on the node the file named");
        assertEquals("status", String.valueOf(said.get(0).detail().get("kind")));
        assertEquals("UNAVAILABLE", String.valueOf(said.get(0).detail().get("status")));
    }

    /** The longest handler span in the run, which is the one that was made slow. */
    private static double handlerRefMs(String yaml) throws Exception {
        var result = Simulate.of(Loader.of(Yaml.parse("rpc.yaml", yaml)),
                RpcFailuresTest.class.getClassLoader());
        double longest = 0;
        for (var s : result.telemetry().spans()) {
            if (!s.kind.equals("handler") || !s.label.endsWith("Map")) continue;
            if (s.t1 - s.t0 > longest) longest = s.t1 - s.t0;
        }
        return longest;
    }
}
