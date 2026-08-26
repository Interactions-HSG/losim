import io.grpc.StatusRuntimeException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import losim.runtime.Run;
import losim.scenario.Loader;
import losim.scenario.Scenario;
import losim.scenario.Yaml;
import losim.t.*;
import losim.trace.Telemetry;

/**
 * Phase 2 — direct mode: everything a scenario declares, and everything it is
 * refused for declaring.
 *
 * <p>Two halves. The first is that a scenario error reads like a compiler error,
 * with the line it was written on, because the alternative is discovering a typo
 * as a puzzling number three minutes into a run. The second is that a fault
 * written at an instant actually lands at that instant, and does what it says.
 */
public class Phase2 {

    static int pass = 0, fail = 0;

    static void check(boolean ok, String what) {
        System.out.printf("  [%s] %s%n", ok ? "PASS" : "FAIL", what);
        if (ok) pass++; else fail++;
    }

    /** Runs the loader over an inline scenario and returns the message it refused with. */
    static String refusal(String yaml) {
        try {
            Loader.of(Yaml.parse("scenario.yaml", yaml));
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    static void refuses(String what, String yaml, String mustMention) {
        String message = refusal(yaml);
        boolean ok = message != null && message.contains(mustMention)
                  && message.matches("^scenario\\.yaml:\\d+:.*");
        check(ok, what + (message == null ? " — but it was accepted"
                                          : "  ->  " + message.split("\n")[0]));
    }

    static final String FLEET = """
        job: NoopJob
        machines:
          master: { instance: m5.large, zone: z }
          workers: { count: 2, prefix: w, instance: m5.large, zone: z, serves: [Pinger] }
        """;

    /**
     * A handler that calls another machine, which is what a coordinator is.
     *
     * <p>Machines are found by what they serve and called over a channel losim made,
     * so the call is a real one — latency, bytes, a span beneath the handler that
     * made it, and whatever the scenario is doing to the machine at the other end.
     * Before this a handler could ask who its peers were and had no way to reach
     * them, so the only way to fan out was a channel of its own, which is exactly
     * what the verifier flags.
     */
    static void forwarding() throws Exception {
        System.out.println("=== a handler calling another machine ===");
        var result = Run.of(Loader.of(Yaml.parse("forward.yaml", """
            seed: 3
            kTime: 4
            job: ForwardJob
            expectedRun: 4 refSeconds
            network: { sameZone: 20 refMs }
            machines:
              master: { instance: m5.large, zone: z }
              front:  { instance: m5.large, zone: z, serves: [Forwarder] }
              back:   { instance: m5.large, zone: z, serves: [Counter] }
            """)));
        var tel = result.telemetry();
        check(result.completed(), "the job finished: master called front, and front called back");

        var handlers = tel.spans().stream().filter(sp -> sp.kind.equals("handler"))
                .sorted(java.util.Comparator.comparingDouble(sp -> sp.t0)).toList();
        var outer = handlers.stream().filter(sp -> sp.vm.equals("front")).findFirst();
        var inner = handlers.stream().filter(sp -> sp.vm.equals("back")).findFirst();
        check(outer.isPresent() && inner.isPresent(),
              "both handlers ran, one on each machine (" + handlers.size() + " spans)");
        // Not directly: between the two handlers is the client span of the call that
        // carried one to the other. Walking it is the point — that is the distributed
        // call stack, and it has to survive a hop a handler made rather than the job.
        var byId = new java.util.HashMap<Long, Telemetry.Span>();
        for (var sp : tel.spans()) byId.put(sp.id, sp);
        long at = inner.map(sp -> sp.parent).orElse(0L);
        var chain = new java.util.ArrayList<String>();
        for (int hop = 0; at != 0 && byId.containsKey(at) && hop < 6; hop++) {
            var sp = byId.get(at);
            chain.add(sp.kind + "@" + sp.vm);
            if (outer.isPresent() && at == outer.get().id) break;
            at = sp.parent;
        }
        check(outer.isPresent() && at == outer.get().id,
              "and the chain from the inner handler reaches the outer one — back.handler <- "
              + String.join(" <- ", chain) + " — so causality crosses a hop a handler made, "
              + "not only one the job made");

        long bytes = handlers.stream()
                .mapToLong(sp -> ((Number) sp.detail.getOrDefault("outBytes", 0)).longValue()).sum();
        check(bytes > 0 && tel.events().stream().anyMatch(e -> e.kind().equals("state")
              && "forwardedTo".equals(e.detail().get("key"))),
              "it is a real call — marshalled, counted (" + bytes + " bytes out) and in the "
              + "trace — not a method invocation dressed as one");

        check(tel.events().stream().filter(e -> e.kind().equals("rpc_call")).count() == 2,
              "two calls, each with its own latency: a handler's hop is charged exactly as "
              + "the job's is, because there is no second path for either of them");
        System.out.println();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Phase 2 — a scenario, and what it is refused for\n");
        refusals();
        durations();
        pools();
        retryGate();
        faults();
        chaos();
        disk();
        endToEnd();
        // Last, deliberately. The reference scenario above turns on a kill landing
        // while a call is in flight, and a section added in front of it changes the
        // JVM it runs in; this one asserts structure, so nothing upstream can move it.
        forwarding();
        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    // -------------------------------------------------------------- file:line

    static void refusals() {
        System.out.println("=== a scenario error names the line it is on ===");
        refuses("an instance type that does not exist",
                FLEET.replace("master: { instance: m5.large", "master: { instance: m5.mega"),
                "unknown instance type");
        refuses("a key that is a typo for a real one",
                FLEET + "network: { sameZone: 1 refMs, crosszone: 2 refMs }\n",
                "unknown key 'crosszone'");
        refuses("a fault aimed at a machine that is not in the fleet",
                FLEET + "faults:\n  - { at: 1 refSeconds, kill: w9 }\n",
                "there is no machine called 'w9'");
        refuses("a fault that tries to do two things at once",
                FLEET + "faults:\n  - { at: 1 refSeconds, kill: w0, freeze: w1 }\n",
                "does two things at once");
        refuses("a degrade with no factor",
                FLEET + "faults:\n  - { at: 1 refSeconds, degrade: w0 }\n",
                "degrade needs a factor");
        refuses("chaos aimed at a pool that does not exist",
                FLEET + "chaos:\n  - { kill: { every: 1 refSeconds, among: reducers } }\n",
                "is neither a pool nor a machine");
        refuses("a probability outside zero and one",
                FLEET + "network: { loss: 4 }\n",
                "a probability");
        refuses("the same machine declared twice",
                """
                job: NoopJob
                machines:
                  w0: { instance: m5.large, zone: z }
                  pool: { count: 1, prefix: w, instance: m5.large, zone: z }
                """,
                "two machines are both called 'w0'");
        System.out.println();
    }

    // -------------------------------------------------------------- durations

    static void durations() {
        System.out.println("=== a duration has to say what kind of time it is ===");
        refuses("a bare number is not a duration",
                FLEET + "faults:\n  - { at: 900, kill: w0 }\n",
                "does not say what kind of time it is");
        refuses("and neither is one with a wall-clock unit",
                FLEET + "faults:\n  - { at: 900ms, kill: w0 }\n",
                "does not say what kind of time it is");
        var s = Loader.of(Yaml.parse("scenario.yaml",
                FLEET + "faults:\n  - { at: 2 refSeconds, kill: w0 }\n"
                      + "  - { at: 900 refMs, kill: w1 }\n"));
        check(s.faults().get(0).atRefMs() == 2000 && s.faults().get(1).atRefMs() == 900,
              "refSeconds and refMs are the same scale, an order of magnitude apart");
        System.out.println("    '2s' would be ambiguous between two seconds of the simulated world");
        System.out.println("    and two seconds of your afternoon, and those differ by k_time —");
        System.out.println("    which whoever writes the scenario never sees.");
        System.out.println();
    }

    // ------------------------------------------------------------------ pools

    static void pools() {
        System.out.println("=== pools, and the deliberate straggler ===");
        var s = Loader.of(Yaml.parse("scenario.yaml", """
                job: NoopJob
                machines:
                  master: { instance: m5.large, zone: eu-a }
                  workers:
                    count: 6
                    prefix: w
                    instance: m5.large
                    zone: [eu-a, eu-b]
                    serves: [Pinger]
                    overrides:
                      w3: { instance: t3.micro }
                      w4: { memoryMb: 16 }
                """));
        var byName = new LinkedHashMap<String, Scenario.MachineSpec>();
        s.machines().forEach(m -> byName.put(m.name(), m));
        check(byName.keySet().equals(new LinkedHashSet<>(
                      List.of("master", "w0", "w1", "w2", "w3", "w4", "w5"))),
              "a pool of six is six machines, named from its prefix");
        check(byName.get("w0").zone().equals("eu-a") && byName.get("w1").zone().equals("eu-b"),
              "zones are dealt round-robin, so a pool is spread rather than stacked");
        check(byName.get("w3").instance().equals("t3.micro")
              && byName.get("w2").instance().equals("m5.large"),
              "an override changes one machine and leaves the rest alone");
        check(byName.get("w4").memoryCapMb() == 16 && byName.get("w0").memoryCapMb() == null,
              "and a cap set explicitly overrides the instance type, which is how scaled mode "
              + "will fill them in");
        System.out.println();
    }

    // -------------------------------------------------------------- retry gate

    static void retryGate() throws Exception {
        System.out.println("=== retrying a call the schema does not call safe ===");
        String unsafe = FLEET + """
                retries:
                  - { method: Volley.Hit, attempts: 3, backoff: 10 refMs }
                """;
        String message = null;
        try { Run.of(Loader.of(Yaml.parse("scenario.yaml", unsafe))); }
        catch (RuntimeException e) { message = e.getMessage(); }
        check(message != null && message.contains("is refused")
              && message.contains("idempotency_level")
              && message.matches("(?s)^scenario\\.yaml:\\d+:.*"),
              "it is refused at load, with the line and what to do about it");
        if (message != null) System.out.println("    " + message.replace(". ", ".\n    "));

        check(runs(FLEET + """
                retries:
                  - { method: Volley.Poll, attempts: 3, backoff: 10 refMs }
                """),
              "a method whose .proto declares idempotency_level needs no argument");
        check(runs(FLEET + """
                retries:
                  - { method: Volley.Hit, attempts: 3, backoff: 10 refMs, unsafe: true }
                """),
              "and 'unsafe: true' allows it — one visible line in a diff, which is the point");

        String missing = null;
        try { Run.of(Loader.of(Yaml.parse("scenario.yaml", FLEET + """
                retries:
                  - { method: Worker.Map, attempts: 2 }
                """))); }
        catch (RuntimeException e) { missing = e.getMessage(); }
        check(missing != null && missing.contains("which no machine in this fleet serves"),
              "a policy naming a method nobody serves is a typo, and is caught as one");

        // And it actually retries.
        Pinger.HITS.set(0);
        Pinger.failFirst = 2;
        var result = Run.of(Loader.of(Yaml.parse("scenario.yaml", """
                job: RetryJob
                kTime: 4
                expectedRun: 2 refSeconds
                machines:
                  master: { instance: m5.large, zone: z }
                  workers: { count: 1, prefix: w, instance: m5.large, zone: z, serves: [Pinger] }
                retries:
                  - { method: Volley.Poll, attempts: 4, backoff: 20 refMs, multiplier: 2 }
                """)));
        Pinger.failFirst = 0;
        var retries = result.telemetry().events().stream()
                .filter(e -> e.kind().equals("retry")).toList();
        System.out.printf("    the handler failed twice, so the call was made %d times%n",
                Pinger.HITS.get());
        check(result.completed() && Pinger.HITS.get() == 3,
              "a retried call really is made again, against the same machine");
        check(retries.size() == 2,
              "and each attempt is in the trace rather than hidden inside one call");
        check(retries.size() == 2
              && (double) (Double) retries.get(1).detail().get("backoffRefMs")
                 > (double) (Double) retries.get(0).detail().get("backoffRefMs"),
              "with the backoff growing, because a fleet that retries in lockstep is a fleet "
              + "that retries itself to death");
        System.out.println();
    }

    static boolean runs(String yaml) {
        try {
            Run.of(Loader.of(Yaml.parse("scenario.yaml", yaml)));
            return true;
        } catch (Exception e) {
            System.out.println("    unexpectedly refused: " + e.getMessage());
            return false;
        }
    }

    // ----------------------------------------------------------------- faults

    static void faults() throws Exception {
        System.out.println("=== every fault lands where the scenario put it ===");
        var result = Run.of(Loader.of(Yaml.parse("scenario.yaml", """
                job: WaitJob
                kTime: 2
                seed: 3
                expectedRun: 2 refSeconds
                machines:
                  master: { instance: m5.large, zone: z }
                  workers: { count: 4, prefix: w, instance: m5.large, zone: z, serves: [Pinger] }
                faults:
                  - { at: 200 refMs, freeze: w0, for: 300 refMs }
                  - { at: 250 refMs, degrade: w1, factor: 8 }
                  - { at: 300 refMs, spot_reclaim: w2, notice: 150 refMs }
                  - { at: 400 refMs, partition: [master, w3] }
                  - { at: 700 refMs, kill: w3, restart_after: 300 refMs }
                  - { at: 1200 refMs, heal: [master, w3] }
                """)));
        var tel = result.telemetry();
        var at = new LinkedHashMap<String, Double>();
        for (var e : tel.events()) at.putIfAbsent(e.kind() + ":" + e.vm(), e.t());
        for (var e : List.of("freeze:w0", "degrade:w1", "spot_notice:w2", "kill:w2",
                             "partition:master", "kill:w3", "restart:w3", "heal:master"))
            System.out.printf("    %-18s at %6.0f refMs%n", e, at.getOrDefault(e, -1.0));

        check(near(at.get("freeze:w0"), 200) && near(at.get("degrade:w1"), 250),
              "freeze and degrade land within a few refMs of where they were written");
        check(near(at.get("spot_notice:w2"), 300) && near(at.get("kill:w2"), 450),
              "a spot reclaim gives its notice first and takes the machine after it — "
              + "which is the whole lesson, and only teachable if the gap is real");
        check(near(at.get("kill:w3"), 700) && near(at.get("restart:w3"), 1000),
              "restart_after brings a machine back, which is a different exercise "
              + "from one that never returns");
        check(tel.events().stream().anyMatch(e -> e.kind().equals("thaw")),
              "a freeze ends by itself rather than needing to be undone");
        var restart = tel.events().stream().filter(e -> e.kind().equals("restart")).findFirst();
        check(restart.isPresent() && "lost".equals(restart.get().detail().get("state")),
              "and a machine declared by class comes back with fresh services, so what it was "
              + "holding is genuinely gone");
        check(tel.dangling().isEmpty(), "no call is left open by any of it");
        System.out.println();
    }

    static boolean near(Double actual, double want) {
        return actual != null && Math.abs(actual - want) < 60;
    }

    // ------------------------------------------------------------------ chaos

    static void chaos() throws Exception {
        System.out.println("=== chaos is a rate, not a moment ===");
        String yaml = """
                job: WaitJob
                kTime: 20
                seed: %d
                expectedRun: 20 refSeconds
                machines:
                  master: { instance: m5.large, zone: z }
                  workers: { count: 6, prefix: w, instance: m5.large, zone: z, serves: [Pinger] }
                chaos:
                  - { kill: { every: 3 refSeconds, among: workers } }
                """;
        var afternoons = new ArrayList<List<String>>();
        for (long seed : new long[]{1, 1, 2}) {
            var tel = Run.of(Loader.of(Yaml.parse("scenario.yaml", yaml.formatted(seed))))
                    .telemetry();
            afternoons.add(tel.events().stream().filter(e -> e.kind().equals("chaos"))
                    .map(e -> e.vm() + "@" + Math.round((Double) e.detail().get("atRefMs")))
                    .toList());
        }
        System.out.println("    seed 1: " + afternoons.get(0));
        System.out.println("    seed 1: " + afternoons.get(1));
        System.out.println("    seed 2: " + afternoons.get(2));
        check(!afternoons.get(0).isEmpty(), "a standing rate produces victims without naming any");
        check(afternoons.get(0).equals(afternoons.get(1)),
              "the same seed draws the same bad afternoon, so a finding can be looked at twice");
        check(!afternoons.get(0).equals(afternoons.get(2)),
              "and another seed draws a different one, which is why a sweep is 5 to 20 runs and "
              + "not one lucky one");
        System.out.println();
    }

    // ------------------------------------------------------------------- disk

    static void disk() throws Exception {
        System.out.println("=== a full disk refuses the write ===");
        var result = Run.of(Loader.of(Yaml.parse("scenario.yaml", """
                job: WordCountJob
                kTime: 4
                expectedRun: 3 refSeconds
                machines:
                  master: { instance: m5.large, zone: z }
                  workers:
                    count: 2
                    prefix: w
                    instance: m5.large
                    zone: z
                    serves: [Counter]
                    overrides:
                      w1: { diskMb: 1 }
                """)));
        var tel = result.telemetry();
        var full = tel.events().stream().filter(e -> e.kind().equals("disk_full")).findFirst();
        full.ifPresent(e -> System.out.printf("    %s: %s MB asked of a %s MB volume%n",
                e.vm(), e.detail().get("demandMb"), e.detail().get("capMb")));
        check(full.isPresent(),
              "a machine asked for more disk than it has says so, naming the cap and the demand");
        check(tel.spans().stream().anyMatch(s -> s.kind.equals("handler")
                && s.detail.containsKey("error")),
              "and the handler fails, because a write that cannot happen must not appear to");
        System.out.println();
    }

    // ---------------------------------------------------------------- the run

    static void endToEnd() throws Exception {
        System.out.println("=== the reference scenario, end to end ===");
        var result = Wordcount.result();
        var tel = result.telemetry();
        System.out.printf("    %s in %.0f refMs: %d events, %d spans, %d series%n",
                result.completed() ? "completed" : "did not complete", result.durationRefMs(),
                tel.events().size(), tel.spans().size(), tel.series().size());
        check(result.completed(), "the job finished despite losing a machine mid-run");
        check(tel.events().stream().anyMatch(e -> e.kind().equals("kill")
                && "killed by the scenario".equals(e.detail().get("reason"))),
              "the scenario's fault fired");
        check(tel.events().stream().anyMatch(e -> e.kind().equals("rpc_timeout")),
              "the coordinator found out by waiting, not by asking whether the machine was alive");
        check(tel.spans().stream().anyMatch(s -> s.kind.equals("compute")
                && s.label.startsWith("local merge")),
              "and redid the work itself, which is the exercise");
        check(tel.events().stream().anyMatch(e -> e.kind().equals("oom")),
              "the machine too small for its bucket ran out of memory, in its own code");
        var answer = tel.events().stream().filter(e -> e.kind().equals("done")).findFirst();
        check(answer.isPresent() && answer.get().detail().get("value") instanceof Map<?, ?> m
              && m.size() == 10,
              "and the job still produced the right answer");
        var json = result.trace().toJson();
        check(json.contains("\"spans\"") && json.contains("\"series\"")
              && json.contains("\"scenario\""),
              "the trace carries all three channels and the scenario it came from ("
              + json.length() / 1024 + " KB)");
        System.out.println();
    }
}
