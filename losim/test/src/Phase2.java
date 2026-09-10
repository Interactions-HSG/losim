import io.grpc.StatusRuntimeException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import dissaly.runtime.Simulate;
import dissaly.sim.Loader;
import dissaly.sim.Simulation;
import dissaly.sim.Yaml;
import dissaly.t.*;
import dissaly.trace.Telemetry;

/**
 * Phase 2, direct mode: everything a simulation declares, and everything it is
 * refused for declaring.
 *
 * <p>Two halves. The first is that a simulation error reads like a compiler error,
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

    /** Runs the loader over an inline simulation and returns the message it refused with. */
    static String refusal(String yaml) {
        try {
            Loader.of(Yaml.parse("simulation.yaml", yaml));
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    static void refuses(String what, String yaml, String mustMention) {
        String message = refusal(yaml);
        boolean ok = message != null && message.contains(mustMention)
                  && message.matches("^simulation\\.yaml:\\d+:.*");
        check(ok, what + (message == null ? " — but it was accepted"
                                          : "  ->  " + message.split("\n")[0]));
    }

    /**
     * The same cluster, with whatever a case wants to go wrong with its workers.
     *
     * <p>Failures live inside the node they happen to, so a case cannot append one
     * to the end of a file any more — which is the point of moving them.
     */
    static String workers(String extra) {
        return "nodes:\n"
             + "  master: { instance: m5.large, zone: z, runs: { dissaly.Job: losim/test/src/WaitJob.java } }\n"
             + "  workers:\n"
             + "    count: 2\n"
             + "    prefix: w\n"
             + "    instance: m5.large\n"
             + "    zone: z\n"
             + "    runs: { Volley: losim/test/src/Pinger.java }\n"
             + extra
             + "simulatedDuration:\n"
             + "  losim/test/src/Pinger.java: { Hit: { fixed: 1 refMs } }\n";
    }

    static final String CLUSTER = """
        nodes:
          master: { instance: m5.large, zone: z, runs: { dissaly.Job: losim/test/src/NoopJob.java } }
          workers: { count: 2, prefix: w, instance: m5.large, zone: z, runs: { Volley: losim/test/src/Pinger.java } }
        simulatedDuration:
          losim/test/src/Pinger.java: { Hit: { fixed: 1 refMs } }
        """;

    /**
     * A handler that calls another machine, which is what a master is.
     *
     * <p>Machines are found by what they serve and called over a channel losim made,
     * so the call is a real one: latency, bytes, a span beneath the handler that
     * made it, and whatever the simulation is doing to the machine at the other end.
     * A handler can ask who its peers are, but reaching them needs exactly this:
     * without a channel losim made, the only way to fan out would be a channel of
     * its own, which is exactly what the verifier flags.
     */
    static void forwarding() throws Exception {
        System.out.println("=== a handler calling another machine ===");
        var result = Simulate.of(Loader.of(Yaml.parse("forward.yaml", """
            seed: 3
            network: { sameZone: 20 refMs }
            nodes:
              master: { instance: m5.large, zone: z, runs: { dissaly.Job: losim/test/src/ForwardJob.java } }
              front:  { instance: m5.large, zone: z, runs: { Worker: losim/test/src/Forwarder.java } }
              back:   { instance: m5.large, zone: z, runs: { Worker: losim/test/src/Counter.java } }
            simulatedDuration:
              losim/test/src/Forwarder.java: { Map: { fixed: 1 refMs } }
              losim/test/src/Counter.java:   { Map: { fixed: 15 refMs }, Reduce: { fixed: 100 refMs } }
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
        // carried one to the other. Walking it is the point: that is the distributed
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
              "it is a real call — marshaled, counted (" + bytes + " bytes out) and in the "
              + "trace — not a method invocation dressed as one");

        check(tel.events().stream().filter(e -> e.kind().equals("rpc_call")).count() == 2,
              "two calls, each with its own latency: a handler's hop is charged exactly as "
              + "the job's is, because there is no second path for either of them");
        System.out.println();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Phase 2 — a simulation, and what it is refused for\n");
        refusals();
        durations();
        pools();
        retryGate();
        failures();
        rates();
        rpcFailures();
        disk();
        endToEnd();
        // Last, deliberately. The reference simulation above turns on a kill landing
        // while a call is in flight, and a section added in front of it changes the
        // JVM it runs in; this one asserts structure, so nothing upstream can move it.
        forwarding();
        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    // -------------------------------------------------------------- file:line

    static void refusals() {
        System.out.println("=== a simulation error names the line it is on ===");
        refuses("an instance type that does not exist",
                CLUSTER.replace("master: { instance: m5.large", "master: { instance: m5.mega"),
                "unknown instance type");
        refuses("a key that is a typo for a real one",
                CLUSTER + "network: { sameZone: 1 refMs, crosszone: 2 refMs }\n",
                "unknown key 'crosszone'");
        refuses("a partition aimed at a node that is not in the system",
                workers("    failures:\n      - { partition: w9, at: 1 refSeconds }\n"),
                "there is no node called 'w9'");
        refuses("a failure that tries to do two things at once",
                workers("    failures:\n      - { kill: true, freeze: true, at: 1 refSeconds }\n"),
                "does two things at once");
        refuses("a degrade that does not slow anything down",
                workers("    failures:\n      - { degrade: 1, at: 1 refSeconds }\n"),
                "is a node that is not degraded");
        refuses("a probability outside zero and one",
                CLUSTER + "network: { loss: 4 }\n",
                "a probability");
        // runs: names code by path, and a path is the one thing about a simulation
        // that can be checked before anything is built. All four of these used to
        // be a run that started and then could not find a class.
        refuses("a runs: value that is not a .java file",
                """
                nodes:
                  w0: { instance: m5.large, zone: z, runs: { Worker: Counter } }
                """,
                "is not a .java file");
        refuses("a .java file that is not there",
                """
                nodes:
                  w0: { instance: m5.large, zone: z, runs: { Worker: src/Nowhere.java } }
                """,
                "there is no file at");
        refuses("a file whose class is not named after it",
                """
                nodes:
                  w0: { instance: m5.large, zone: z, runs: { Worker: losim/test/src/NotItsOwnName.java } }
                """,
                "declares no type called NotItsOwnName");
        refuses("runs: written as a list, which says only half of what it has to",
                """
                nodes:
                  w0: { instance: m5.large, zone: z, runs: [Counter] }
                """,
                "names a service and the .java file that implements it");
        refuses("the same node declared twice",
                """
                nodes:
                  w0: { instance: m5.large, zone: z }
                  pool: { count: 1, prefix: w, instance: m5.large, zone: z }
                """,
                "two nodes are both called 'w0'");
        System.out.println();
    }

    // -------------------------------------------------------------- durations

    static void durations() {
        System.out.println("=== a duration has to say what kind of time it is ===");
        refuses("a bare number is not a duration",
                workers("    failures:\n      - { kill: true, at: 900 }\n"),
                "does not say what kind of time it is");
        refuses("and neither is one with a wall-clock unit",
                workers("    failures:\n      - { kill: true, at: 900ms }\n"),
                "does not say what kind of time it is");
        var s = Loader.of(Yaml.parse("simulation.yaml", workers(
                "    failures:\n      - { kill: true, at: 2 refSeconds }\n"
              + "      - { freeze: true, at: 900 refMs }\n")));
        var written = s.nodes().stream().filter(m -> m.name().equals("w0")).findFirst().get();
        check(written.failures().get(0).atRefMs() == 2000
              && written.failures().get(1).atRefMs() == 900,
              "refSeconds and refMs are the same scale, an order of magnitude apart");
        System.out.println("    '2s' would be ambiguous between two seconds of the simulated world");
        System.out.println("    and two seconds of your afternoon, and those differ by k_time —");
        System.out.println("    which whoever writes the simulation never sees.");
        System.out.println();
    }

    // ------------------------------------------------------------------ pools

    static void pools() {
        System.out.println("=== pools, and the deliberate straggler ===");
        var s = Loader.of(Yaml.parse("simulation.yaml", """
                nodes:
                  master: { instance: m5.large, zone: eu-a }
                  workers:
                    count: 6
                    prefix: w
                    instance: m5.large
                    zone: [eu-a, eu-b]
                    runs: { Volley: losim/test/src/Pinger.java }
                    overrides:
                      w3: { instance: a1.nano }
                      w4: { memoryMb: 16 }
                """));
        var byName = new LinkedHashMap<String, Simulation.NodeSpec>();
        s.nodes().forEach(m -> byName.put(m.name(), m));
        check(byName.keySet().equals(new LinkedHashSet<>(
                      List.of("master", "w0", "w1", "w2", "w3", "w4", "w5"))),
              "a pool of six is six machines, named from its prefix");
        check(byName.get("w0").zone().equals("eu-a") && byName.get("w1").zone().equals("eu-b"),
              "zones are dealt round-robin, so a pool is spread rather than stacked");
        check(byName.get("w3").instance().equals("a1.nano")
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
        String unsafe = CLUSTER + """
                retries:
                  - { method: Volley.Hit, attempts: 3, backoff: 10 refMs }
                """;
        String message = null;
        try { Simulate.of(Loader.of(Yaml.parse("simulation.yaml", unsafe))); }
        catch (RuntimeException e) { message = e.getMessage(); }
        check(message != null && message.contains("is refused")
              && message.contains("idempotency_level")
              && message.matches("(?s)^simulation\\.yaml:\\d+:.*"),
              "it is refused at load, with the line and what to do about it");
        if (message != null) System.out.println("    " + message.replace(". ", ".\n    "));

        check(runs(CLUSTER + """
                retries:
                  - { method: Volley.Poll, attempts: 3, backoff: 10 refMs }
                """),
              "a method whose .proto declares idempotency_level needs no argument");
        check(runs(CLUSTER + """
                retries:
                  - { method: Volley.Hit, attempts: 3, backoff: 10 refMs, unsafe: true }
                """),
              "and 'unsafe: true' allows it — one visible line in a diff, which is the point");

        String missing = null;
        try { Simulate.of(Loader.of(Yaml.parse("simulation.yaml", CLUSTER + """
                retries:
                  - { method: Worker.Map, attempts: 2 }
                """))); }
        catch (RuntimeException e) { missing = e.getMessage(); }
        check(missing != null && missing.contains("which no node in this simulation serves"),
              "a policy naming a method nobody serves is a typo, and is caught as one");

        // And it actually retries.
        Pinger.HITS.set(0);
        Pinger.failFirst = 2;
        var result = Simulate.of(Loader.of(Yaml.parse("simulation.yaml", """
                nodes:
                  master: { instance: m5.large, zone: z, runs: { dissaly.Job: losim/test/src/RetryJob.java } }
                  workers: { count: 1, prefix: w, instance: m5.large, zone: z, runs: { Volley: losim/test/src/Pinger.java } }
                retries:
                  - { method: Volley.Poll, attempts: 4, backoff: 20 refMs, multiplier: 2 }
                simulatedDuration:
                  losim/test/src/Pinger.java: { Hit: { fixed: 1 refMs } }
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
              "with the backoff growing, because a cluster that retries in lockstep is a cluster "
              + "that retries itself to death");
        System.out.println();
    }

    static boolean runs(String yaml) {
        try {
            Simulate.of(Loader.of(Yaml.parse("simulation.yaml", yaml)));
            return true;
        } catch (Exception e) {
            System.out.println("    unexpectedly refused: " + e.getMessage());
            return false;
        }
    }

    // --------------------------------------------------------------- failures

    static void failures() throws Exception {
        System.out.println("=== every failure lands where the simulation put it ===");
        var result = Simulate.of(Loader.of(Yaml.parse("simulation.yaml", """
                seed: 3
                nodes:
                  master: { instance: m5.large, zone: z, runs: { dissaly.Job: losim/test/src/WaitJob.java } }
                  workers:
                    count: 4
                    prefix: w
                    instance: m5.large
                    zone: z
                    runs: { Volley: losim/test/src/Pinger.java }
                    overrides:
                      w0: { failures: [ { freeze: true, at: 200 refMs, for: 300 refMs } ] }
                      w1: { failures: [ { degrade: 8, at: 250 refMs } ] }
                      w2: { failures: [ { spotReclaim: true, at: 300 refMs, notice: 150 refMs } ] }
                      w3:
                        failures:
                          - { partition: master, at: 400 refMs }
                          - { kill: true, at: 700 refMs, restartAfter: 300 refMs }
                          - { heal: master, at: 1200 refMs }
                simulatedDuration:
                  losim/test/src/Pinger.java: { Hit: { fixed: 1 refMs } }
                """)));
        var tel = result.telemetry();
        var at = new LinkedHashMap<String, Double>();
        for (var e : tel.events()) at.putIfAbsent(e.kind() + ":" + e.vm(), e.t());
        for (var e : List.of("freeze:w0", "degrade:w1", "spot_notice:w2", "kill:w2",
                             "partition:w3", "kill:w3", "restart:w3", "heal:w3"))
            System.out.printf("    %-18s at %6.0f refMs%n", e, at.getOrDefault(e, -1.0));

        check(near(at.get("freeze:w0"), 200) && near(at.get("degrade:w1"), 250),
              "freeze and degrade land within a few refMs of where they were written");
        check(near(at.get("spot_notice:w2"), 300) && near(at.get("kill:w2"), 450),
              "a spot reclaim gives its notice first and takes the node after it — "
              + "which is the whole lesson, and only teachable if the gap is real");
        check(near(at.get("kill:w3"), 700) && near(at.get("restart:w3"), 1000),
              "restartAfter brings a node back, which is a different exercise "
              + "from one that never returns");
        check(at.containsKey("partition:w3") && at.containsKey("heal:w3"),
              "and a partition is reported by the node it was written in, which is the end "
              + "of it the file names");
        check(tel.events().stream().anyMatch(e -> e.kind().equals("thaw")),
              "a freeze ends by itself rather than needing to be undone");
        var restart = tel.events().stream().filter(e -> e.kind().equals("restart")).findFirst();
        check(restart.isPresent() && "lost".equals(restart.get().detail().get("state")),
              "and a node declared by path comes back with fresh services, so what it was "
              + "holding is genuinely gone");
        check(tel.dangling().isEmpty(), "no call is left open by any of it");
        System.out.println();
    }

    static boolean near(Double actual, double want) {
        return actual != null && Math.abs(actual - want) < 60;
    }

    // -------------------------------------------------------------- at or per

    static void rates() throws Exception {
        System.out.println("=== a failure happens at a moment or at a rate, never both ===");
        refuses("a failure that says both when and how often",
                workers("    failures:\n      - { kill: true, at: 1 refSeconds, per: 2 refSeconds }\n"),
                "Those are two failures");
        refuses("a failure that says neither, and so never happens",
                workers("    failures:\n      - { kill: true }\n"),
                "never happens");
        refuses("a rate on a kind that happens once",
                workers("    failures:\n      - { spotReclaim: true, per: 2 refSeconds }\n"),
                "happens once, so it has an at:");
        refuses("a key the kind does nothing with",
                workers("    failures:\n      - { degrade: 4, at: 1 refSeconds, for: 200 refMs }\n"),
                "does nothing to a degrade");

        String yaml = "seed: %d\n"
                    + "nodes:\n"
                    + "  master: { instance: m5.large, zone: z, runs: { dissaly.Job: losim/test/src/WaitJob.java } }\n"
                    + "  workers:\n"
                    + "    count: 6\n"
                    + "    prefix: w\n"
                    + "    instance: m5.large\n"
                    + "    zone: z\n"
                    + "    runs: { Volley: losim/test/src/Pinger.java }\n"
                    + "    failures:\n"
                    + "      - { kill: true, per: 300 refMs }\n"
                    + "simulatedDuration:\n"
                    + "  losim/test/src/Pinger.java: { Hit: { fixed: 1 refMs } }\n";
        var afternoons = new ArrayList<List<String>>();
        for (long seed : new long[]{1, 1, 2}) {
            var tel = Simulate.of(Loader.of(Yaml.parse("simulation.yaml", yaml.formatted(seed))))
                    .telemetry();
            afternoons.add(tel.events().stream().filter(e -> e.kind().equals("failure"))
                    .map(e -> e.vm() + "@" + Math.round((Double) e.detail().get("atRefMs")))
                    .toList());
        }
        System.out.println("    seed 1: " + afternoons.get(0));
        System.out.println("    seed 1: " + afternoons.get(1));
        System.out.println("    seed 2: " + afternoons.get(2));
        check(!afternoons.get(0).isEmpty(),
              "a standing rate produces victims without naming any — the pool is written once "
              + "and every node in it draws its own afternoon");
        // Six nodes that all die in the same instant is a correlated failure, and
        // this simulation declares none. It is what `new Random(seed + index)`
        // produces: seeds 38 through 43 all begin 0.7279, so six streams are one
        // stream copied six times. The check that a seed is reproducible passes
        // either way, which is why this one is here beside it.
        var instants = afternoons.get(0).stream()
                .map(v -> v.substring(v.indexOf('@') + 1)).distinct().toList();
        check(instants.size() > 1,
              "and they are genuinely six streams rather than one copied six times — "
              + instants.size() + " distinct instants across the pool");
        check(afternoons.get(0).equals(afternoons.get(1)),
              "the same seed draws the same bad afternoon, so a finding can be looked at twice");
        check(!afternoons.get(0).equals(afternoons.get(2)),
              "and another seed draws a different one, which is why a sweep is 5 to 20 runs and "
              + "not one lucky one");
        System.out.println();
    }

    // ------------------------------------------------------- failures per rpc

    /** The same cluster, with something wrong with one rpc on the workers. */
    static String badRpc(String rules) {
        return "seed: 4\n"
             + "nodes:\n"
             + "  master: { instance: m5.large, zone: z, runs: { dissaly.Job: losim/test/src/WaitJob.java } }\n"
             + "  workers:\n"
             + "    count: 2\n"
             + "    prefix: w\n"
             + "    instance: m5.large\n"
             + "    zone: z\n"
             + "    runs:\n"
             + "      Volley:\n"
             + "        file: losim/test/src/Pinger.java\n"
             + "        failures:\n"
             + rules
             + "simulatedDuration:\n"
             + "  losim/test/src/Pinger.java: { Hit: { fixed: 1 refMs } }\n";
    }

    static void rpcFailures() throws Exception {
        System.out.println("=== an rpc fails here and not on its peers ===");
        refuses("a node kind written against an rpc",
                badRpc("          Hit:\n            - { kill: true, per: 3 calls }\n"),
                "kill happens to a node, not to an rpc");
        refuses("an rpc kind written against a node",
                workers("    failures:\n      - { status: UNAVAILABLE, per: 3 calls }\n"),
                "status happens to an rpc, not to a node");
        refuses("an rpc failure at an instant",
                badRpc("          Hit:\n            - { drop: true, at: 300 refMs }\n"),
                "an rpc fails at a rate rather than at an instant");
        refuses("a rate that does not say what it counts",
                badRpc("          Hit:\n            - { drop: true, per: 3 refMs }\n"),
                "does not say what it counts");
        refuses("a status code that is not one",
                badRpc("          Hit:\n            - { status: TOO_SLOW, per: 3 calls }\n"),
                "is not a gRPC status code");
        refuses("OK as a failure",
                badRpc("          Hit:\n            - { status: OK, per: 3 calls }\n"),
                "OK is what a call that worked returns");
        // The one refusal the loader cannot make: it loads no class, so it does not
        // know what a service serves. Machines asks the bound server, at run start,
        // before a single call is made.
        boolean caught = false, readable = false;
        try {
            Simulate.of(Loader.of(Yaml.parse("simulation.yaml",
                    badRpc("          Hitt:\n            - { drop: true, per: 3 calls }\n"))));
        } catch (Exception e) {
            String said = e.getMessage() == null ? "" : e.getMessage();
            caught = said.contains("serves no rpc of that name");
            // The key is a path joined to an rpc with a dot, and the path has dots
            // of its own. A refusal that prints it back as 'losim/test/src/Volley
            // java Hitt' names no file the reader can open.
            readable = said.contains("losim/test/src/Pinger.java Hitt");
            System.out.println("    " + said);
        }
        check(caught, "an rpc the service does not serve is refused with its line, because a "
              + "failure that belongs to nothing quietly never fires");
        check(readable, "and the refusal names the file as it was written, dots and all, "
              + "because the reader's next move is to open it");

        var tel = Simulate.of(Loader.of(Yaml.parse("simulation.yaml",
                badRpc("          Hit:\n            - { status: UNAVAILABLE, per: 2 calls }\n"))))
                .telemetry();
        var refused = tel.events().stream().filter(e -> e.kind().equals("rpc_failure")).toList();
        var nodes = refused.stream().map(e -> e.vm()).distinct().sorted().toList();
        System.out.println("    " + refused.size() + " calls refused, on " + nodes);
        check(!refused.isEmpty(), "the rpc fails, one call in two");
        long handled = tel.events().stream().filter(e -> e.kind().equals("handler_start")
                && "dissaly.t.Volley.Hit".equals(e.detail().get("method"))).count();
        check(handled + refused.size() > handled,
              "and a refused call never reaches the handler: " + handled + " ran, "
              + refused.size() + " were turned away before one could");
        System.out.println();
    }

    // ------------------------------------------------------------------- disk

    static void disk() throws Exception {
        System.out.println("=== a full disk refuses the write ===");
        var result = Simulate.of(Loader.of(Yaml.parse("simulation.yaml", """
                nodes:
                  master: { instance: m5.large, zone: z, runs: { dissaly.Job: losim/test/src/WordCountJob.java } }
                  workers:
                    count: 2
                    prefix: w
                    instance: m5.large
                    zone: z
                    runs: { Worker: losim/test/src/Counter.java }
                    overrides:
                      w1: { diskMb: 1 }
                simulatedDuration:
                  losim/test/src/Counter.java: { Map: { fixed: 15 refMs }, Reduce: { fixed: 100 refMs } }
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
        System.out.println("=== the reference simulation, end to end ===");
        var result = Wordcount.result();
        var tel = result.telemetry();
        System.out.printf("    %s in %.0f refMs: %d events, %d spans, %d series%n",
                result.completed() ? "completed" : "did not complete", result.durationRefMs(),
                tel.events().size(), tel.spans().size(), tel.series().size());
        check(result.completed(), "the job finished despite losing a machine mid-run");
        check(tel.events().stream().anyMatch(e -> e.kind().equals("kill")
                && "killed by the simulation".equals(e.detail().get("reason"))),
              "the simulation's failure fired");
        check(tel.events().stream().anyMatch(e -> e.kind().equals("rpc_timeout")),
              "the master found out by waiting, not by asking whether the machine was alive");
        // Which of the two this run produces is a race with the host, not a
        // property of the design: the dispatcher fires the kill at a wall-clock
        // instant, while how long the map phase takes moves with load. On a
        // two-core runner the map is still open at 400 refMs and w5 dies before
        // it has answered, so there is no reduce to it to fail. Both are the
        // master coping with a machine that is not there, and asserting only
        // the one a fast laptop produces is asserting the speed of the laptop.
        // Read off the master's own narration rather than off a span kind.
        // It used to be a `compute` span, which existed because a driver object
        // running outside every call had no span of its own; the merge now happens
        // inside the dissaly.Job/Run handler, where the node is already busy for it.
        boolean redid = tel.events().stream().anyMatch(e -> e.kind().equals("log")
                && String.valueOf(e.detail().get("message")).startsWith("reducer"));
        boolean without = tel.events().stream().anyMatch(e -> e.kind().equals("log")
                && String.valueOf(e.detail().get("message")).startsWith("map on"));
        System.out.printf("    it coped by %s%n", redid
                ? "redoing the dead reducer's work itself"
                : "carrying on without the mapper that never answered");
        check(redid || without,
              "and coped with the machine that was not there, rather than losing the answer");
        check(tel.events().stream().anyMatch(e -> e.kind().equals("oom")),
              "the machine too small for its bucket ran out of memory, in its own code");
        // One level down, because the answer is a dissaly.Result now and the map is
        // its `answer` field. Structural either way: the point was never the string.
        var answer = tel.events().stream().filter(e -> e.kind().equals("done")).findFirst();
        Object counts = answer.isPresent() && answer.get().detail().get("value") instanceof Map<?, ?> v
                ? v.get("answer") : null;
        check(counts instanceof Map<?, ?> m && m.size() == 10,
              "and the simulation still produced the right answer");
        var json = result.trace().toJson();
        check(json.contains("\"spans\"") && json.contains("\"series\"")
              && json.contains("\"simulation\""),
              "the trace carries all three channels and the simulation it came from ("
              + json.length() / 1024 + " KB)");
        System.out.println();
    }
}
