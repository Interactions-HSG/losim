import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.*;
import losim.api.Takes;
import losim.api.Losim;
import losim.runtime.Fleet;
import losim.runtime.Machine;
import losim.runtime.Net;
import losim.runtime.Wire;
import losim.scale.Fit;
import losim.t.*;
import losim.time.Clock;
import losim.trace.Telemetry;
import losim.trace.Trace;

/**
 * Phase 1 — the fleet, checked against what it claimed.
 *
 * Four of these are the phase's acceptance criteria and are marked as such. The
 * rest are the mechanisms those four rest on: if the ambient context does not
 * reach a handler, or spans do not cross the RPC boundary, then the acceptance
 * checks are measuring something other than what they name.
 */
public class Phase1 {

    static int pass = 0, fail = 0;

    static void check(boolean ok, String what) {
        System.out.printf("  [%s] %s%n", ok ? "PASS" : "FAIL", what);
        if (ok) pass++; else fail++;
    }

    static double median(List<Double> xs) {
        var s = new ArrayList<>(xs);
        Collections.sort(s);
        return s.isEmpty() ? 0 : s.get(s.size() / 2);
    }

    // ------------------------------------------------------------------ fixtures

    /** A handler with a declared cost, so the interceptor has something to sleep. */
    static final class Costed extends WorkerBase {
        @Takes(refMs = 500)
        @Override protected Counts map(Chunk c) {
            return Counts.newBuilder().putCounts("seen", c.getLines()).build();
        }
    }

    /** A handler whose cost is proportional to what it was given. */
    /** A handler whose duration only the running program knows, so no annotation can carry it. */
    static final class Waiting extends WorkerBase {
        @Override protected Counts map(Chunk c) {
            Losim.current().sleep(500);
            return Counts.newBuilder().putCounts("waited", 500).build();
        }
    }

    static final class PerRecord extends WorkerBase {
        @Takes(refNsPerRecord = 1_000_000)             // 1 refMs a record
        @Override protected Counts map(Chunk c) {
            Losim.current().records(c.getLines());
            return Counts.newBuilder().putCounts("seen", c.getLines()).build();
        }
    }

    /** A handler that says who served it, so the ambient context can be checked. */
    static final class Reporter extends WorkerBase {
        @Override protected Counts map(Chunk c) {
            Losim.current().reveal("served-by", Losim.current().machine());
            return Counts.newBuilder()
                    .putCounts(Losim.current().machine(), 1)
                    .putCounts("peers", Losim.current().peersServing("Worker").size())
                    .build();
        }
    }

    /**
     * How far an exponent moves when nothing has changed — as a noise scale.
     *
     * <p>Deliberately not {@link Fit#wobble}, which is max minus min and is the right
     * statistic where the engine uses it: an error bar should be conservative, and a
     * resource whose exponent lands somewhere else on any one seed set is a resource
     * to refuse. This number has the opposite job. It is the scale a bend is measured
     * against, so one anomalous set — a heap walk landing badly on one rung, which
     * D12 says happens — would make the scale a fact about that set rather than about
     * the noise. Dropping the extreme at each end makes it a fact about the noise,
     * for the same reason the bend beside it is a median and not a mean.
     */
    static double spread(List<Double> betas) {
        var sorted = new ArrayList<>(betas);
        java.util.Collections.sort(sorted);
        if (sorted.size() < 5) return Fit.wobble(sorted);
        return sorted.get(sorted.size() - 2) - sorted.get(1);
    }

    /** The gross duration of the handler span that ran on one machine. */
    static double byMachine(Fleet fleet, String machine) {
        return fleet.telemetry().spans().stream()
                .filter(s -> s.kind.equals("handler") && s.vm.equals(machine))
                .mapToDouble(s -> s.grossMs()).max().orElse(0);
    }

    static Fleet fleet(double kTime, Telemetry.Level level) {
        return new Fleet(new Telemetry(new Clock(kTime, Clock.measureCorrection()), level));
    }

    // ---------------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        System.out.println("Phase 1 — one in-process server per machine, losim as interceptors\n");

        oneCall();
        fireAndForget();
        absentContext();
        declaredCost();
        contention();
        network();
        faultPlacement();
        traceShape();
        exclusion();
        observerLaw();

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------- one call, end to end

    static void oneCall() throws Exception {
        System.out.println("=== one unary call over real gRPC ===");
        try (var fleet = fleet(100, Telemetry.Level.FULL)) {
            var client = fleet.machine("client", "m5.large", "z");
            fleet.machine("srv", "m5.large", "z").serving(new Reporter());
            // A second machine offering the same service, so that "who else serves
            // this" is a question with a real answer rather than a count of one.
            fleet.machine("spare", "m5.large", "z").serving(new Reporter());
            ManagedChannel ch = client.channelTo("srv");
            var req = Chunk.newBuilder().setText("a b c").setLines(3).build();
            Counts got = client.submit(() -> WorkerGrpc.newBlockingStub(ch).map(req)).get();
            ch.shutdownNow();

            check(got.getCountsOrDefault("srv", 0) == 1,
                  "the handler ran on the machine that was serving, and knew it");
            check(got.getCountsOrDefault("peers", 0) == 1,
                  "peers are found by service name, not hostname — and the handler's own "
                  + "machine is not among them, because a blocking call to itself would "
                  + "starve the pool it is already holding a thread of");

            var tel = fleet.telemetry();
            var spans = tel.spans();
            var rpc = spans.stream().filter(s -> s.kind.equals("rpc")).findFirst().orElseThrow();
            var handler = spans.stream().filter(s -> s.kind.equals("handler")).findFirst().orElseThrow();

            // ---- ACCEPTANCE: byte counts come from marshalling, not the transport
            long expectedIn  = req.getSerializedSize() + Wire.FRAMING_BYTES;
            long expectedOut = got.getSerializedSize() + Wire.FRAMING_BYTES;
            long countedIn   = ((Number) handler.detail.get("inBytes")).longValue();
            long countedOut  = ((Number) handler.detail.get("outBytes")).longValue();
            System.out.printf("    request %d bytes + %d framing, response %d + %d%n",
                    req.getSerializedSize(), Wire.FRAMING_BYTES,
                    got.getSerializedSize(), Wire.FRAMING_BYTES);
            check(countedIn == expectedIn && countedOut == expectedOut,
                  "ACCEPTANCE: counted bytes are getSerializedSize() plus declared framing "
                  + "(" + countedIn + "/" + expectedIn + ", " + countedOut + "/" + expectedOut + ")");
            check(fleet.machine("srv").bytesIn() == expectedIn
                  && fleet.machine("srv").bytesOut() == expectedOut,
                  "and the machine's own totals agree with the span's");

            check(handler.parent == rpc.id,
                  "the server span opened under the caller's span, so causality crossed the RPC");
            check(handler.label.equals("losim.t.Worker.Map") && !handler.label.contains("/"),
                  "method is dotted (" + handler.label + "), which every view downstream requires");
            check(tel.events().stream().anyMatch(e -> e.kind().equals("state")
                  && "served-by".equals(e.detail().get("key"))),
                  "reveal() from inside the handler reached the trace");

            // ---- ACCEPTANCE: no span dangles
            check(tel.dangling().isEmpty(),
                  "ACCEPTANCE: no span dangles (" + spans.size() + " opened, all closed)");
        }
        System.out.println();
    }

    // ------------------------------------------------------------ fire and forget

    static void fireAndForget() throws Exception {
        System.out.println("=== fire-and-forget, which is not a second messaging path ===");
        try (var fleet = fleet(100, Telemetry.Level.FULL)) {
            var a = fleet.machine("a", "m5.large", "z");
            var arrived = new CountDownLatch(5);
            // The callee is slow on purpose: if the caller were blocked, five of
            // these would take five times as long as one.
            fleet.machine("b", "m5.large", "z").serving(new VolleyBase() {
                @Takes(refMs = 300)
                @Override protected void hit(Ping p) { arrived.countDown(); }
            });
            ManagedChannel ch = a.channelTo("b");
            var stub = VolleyGrpc.newStub(ch);

            long t0 = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                stub.hit(Ping.newBuilder().setSeq(i).setFrom("a").build(),
                         new StreamObserver<Empty>() {
                             @Override public void onNext(Empty e) { }
                             @Override public void onError(Throwable t) { }
                             @Override public void onCompleted() { }
                         });
            }
            double returnedMs = (System.nanoTime() - t0) / 1e6;
            boolean all = arrived.await(30, TimeUnit.SECONDS);
            ch.shutdownNow();

            // 300 refMs of cost at k_time 100 is 3ms of real sleep per call.
            System.out.printf("    five async calls returned to the caller in %.2f ms%n", returnedMs);
            check(all, "all five arrived");
            // ---- ACCEPTANCE: the caller was not blocked
            check(returnedMs < 5.0,
                  String.format("ACCEPTANCE: an Empty-returning async call did not block its "
                              + "caller (%.2f ms for five calls costing 3 ms each)", returnedMs));

            var tel = fleet.telemetry();
            check(tel.events().stream().filter(e -> e.kind().equals("rpc_call")).count() == 5
                  && tel.events().stream().filter(e -> e.kind().equals("handler_end")).count() == 5,
                  "both directions appear in the trace, as for any other call");
            check(tel.dangling().isEmpty(), "and none of their spans dangles");
        }
        System.out.println();
    }

    // ----------------------------------------------------------- outside a run

    static void absentContext() {
        System.out.println("=== a handler called with nothing running ===");
        var handler = new Reporter();
        // Not the Reporter — it asks for state. A recording-only handler must be
        // callable from a bare test with no simulation at all.
        var counter = new WorkerBase() {
            @Override protected Counts map(Chunk c) {
                Losim.current().reveal("emitted", 3);
                Losim.current().log("counted");
                Losim.current().records(c.getLines());
                return Counts.newBuilder().putCounts("a", 1).build();
            }
        };
        Counts got = counter.map(Chunk.newBuilder().setText("a").setLines(1).build());
        check(got.getCountsOrDefault("a", 0) == 1,
              "a handler runs, and returns, with no simulation running at all");
        check(!Losim.current().isRunning(), "and knows nothing is running");

        boolean threw = false;
        try { handler.map(Chunk.getDefaultInstance()); }
        catch (IllegalStateException e) { threw = e.getMessage().contains("no simulation is running"); }
        check(threw, "asking for state outside a run throws rather than fabricating a fleet");
        System.out.println();
    }

    // -------------------------------------------------------------- declared cost

    static void declaredCost() throws Exception {
        System.out.println("=== @Takes, in reference milliseconds ===");
        try (var fleet = fleet(100, Telemetry.Level.FULL)) {
            var c = fleet.machine("c", "m5.large", "z");
            fleet.machine("s", "m5.large", "z").serving(new Costed());
            fleet.machine("w", "m5.large", "z").serving(new Waiting());
            fleet.machine("dc", "m5.large", "z").serving(new Costed()).degrade(2);
            fleet.machine("dw", "m5.large", "z").serving(new Waiting()).degrade(2);
            ManagedChannel ch = c.channelTo("s");
            long t0 = System.nanoTime();
            c.submit(() -> WorkerGrpc.newBlockingStub(ch)
                    .map(Chunk.newBuilder().setLines(1).build())).get();
            double realMs = (System.nanoTime() - t0) / 1e6;
            ch.shutdownNow();

            var handler = fleet.telemetry().spans().stream()
                    .filter(s -> s.kind.equals("handler")).findFirst().orElseThrow();
            System.out.printf("    500 refMs at k_time 100 -> %.2f ms of real time, "
                            + "%.0f refMs on the simulated clock%n", realMs, handler.grossMs());
            check(realMs > 3.5 && realMs < 60,
                  String.format("the cost was slept compressed by k_time, not at full size (%.1f ms)", realMs));
            check(handler.grossMs() > 400,
                  String.format("and the simulated clock reports it at reference size (%.0f refMs)",
                          handler.grossMs()));

            // The same duration, written by the program rather than by an annotation.
            // A backoff that grows with the attempt cannot be an annotation at all, and
            // Thread.sleep would be the one duration in the run k_time does not touch.
            ManagedChannel w = c.channelTo("w");
            var chunk = Chunk.newBuilder().setLines(1).build();
            long t1 = System.nanoTime();
            c.submit(() -> WorkerGrpc.newBlockingStub(w).map(chunk)).get();
            double waitReal = (System.nanoTime() - t1) / 1e6;
            w.shutdownNow();
            var waited = byMachine(fleet, "w");
            System.out.printf("    sleep(500 refMs) at k_time 100 -> %.2f ms of real time, "
                            + "%.0f refMs on the simulated clock%n", waitReal, waited);
            check(waitReal > 3.5 && waitReal < 60 && waited > 400, String.format(
                    "Losim.current().sleep() divides by k_time exactly as @Takes does (%.1f ms "
                    + "real, %.0f refMs simulated) — a wait is a declared duration, and every "
                    + "declared duration is reference time", waitReal, waited));
            check(fleet.telemetry().events().stream().anyMatch(e -> e.kind().equals("sleep")),
                  "and it is on the timeline, so a stretch of waiting can be told from a "
                  + "stretch of doing nothing");

            // Waiting is not work, and this is where that stops being a slogan.
            ManagedChannel dc = c.channelTo("dc"), dw = c.channelTo("dw");
            c.submit(() -> WorkerGrpc.newBlockingStub(dc).map(chunk)).get();
            c.submit(() -> WorkerGrpc.newBlockingStub(dw).map(chunk)).get();
            dc.shutdownNow(); dw.shutdownNow();
            double cost = byMachine(fleet, "dc"), wait = byMachine(fleet, "dw");
            System.out.printf("    on a machine at half speed: @Takes(500) -> %.0f refMs, "
                            + "sleep(500) -> %.0f refMs  (x%.2f)%n", cost, wait, cost / wait);
            // The ratio, not the two figures: both carry the same constant of call
            // overhead, and only the ratio says which of them the degrade multiplied.
            check(cost / wait > 1.4, String.format(
                    "@Takes stretches on a degraded machine and sleep does not (%.0f vs %.0f "
                    + "refMs, x%.2f) — a machine at half speed computes slower, but it does not "
                    + "wait longer, and that difference is why a wait is not an annotation",
                    cost, wait, cost / wait));
        }

        try (var fleet = fleet(100, Telemetry.Level.FULL)) {
            var c = fleet.machine("c", "m5.large", "z");
            fleet.machine("s", "m5.large", "z").serving(new PerRecord());
            ManagedChannel ch = c.channelTo("s");
            var stub = WorkerGrpc.newBlockingStub(ch);
            c.submit(() -> stub.map(Chunk.newBuilder().setLines(0).build())).get();
            c.submit(() -> stub.map(Chunk.newBuilder().setLines(400).build())).get();
            ch.shutdownNow();
            var spans = fleet.telemetry().spans().stream()
                    .filter(s -> s.kind.equals("handler"))
                    .sorted(Comparator.comparingDouble(s -> s.t0)).toList();
            double none = spans.get(0).grossMs(), many = spans.get(1).grossMs();
            System.out.printf("    0 records -> %.0f refMs, 400 records -> %.0f refMs%n", none, many);
            check(many - none > 300,
                  "refNsPerRecord is charged against what the handler said it processed");
            check(spans.get(1).records.get() == 400,
                  "and the count itself is in the trace, which is what the scaler engine fits against");
        }
        System.out.println();
    }

    // ---------------------------------------------------------------- contention

    static void contention() throws Exception {
        System.out.println("=== the executor is the vCPU model ===");
        try (var fleet = fleet(10, Telemetry.Level.NO_PAYLOAD)) {
            var c = fleet.machine("c", "m5.2xlarge", "z");
            fleet.machine("two", "m5.large", "z").serving(new Costed());   // 2 vCPU, 500 refMs
            ManagedChannel ch = c.channelTo("two");
            var stub = WorkerGrpc.newBlockingStub(ch);
            var calls = new ArrayList<Future<?>>();
            long t0 = System.nanoTime();
            for (int i = 0; i < 8; i++)
                calls.add(c.submit(() -> stub.map(Chunk.newBuilder().setLines(1).build())));
            for (Future<?> f : calls) f.get(60, TimeUnit.SECONDS);
            double refMs = (System.nanoTime() - t0) / 1e6 * 10;
            ch.shutdownNow();
            System.out.printf("    8 calls x 500 refMs into a 2-vCPU machine took %.0f refMs%n", refMs);
            check(refMs > 1600,
                  String.format("four waves of two, not one wave of eight (%.0f refMs, not 500)", refMs));
            check(fleet.telemetry().events().stream().anyMatch(e -> e.kind().equals("queue_wait")),
                  "and the calls that waited for a core said so");
        }
        System.out.println();
    }

    // ------------------------------------------------------------------- network

    /** Every way a message fails to arrive, and what the caller can tell about it. */
    static void network() throws Exception {
        System.out.println("=== the network: latency, loss, partitions, and a machine that is gone ===");

        // --- latency, in reference milliseconds like everything else
        try (var fleet = new Fleet(new Telemetry(new Clock(100, Clock.measureCorrection())),
                                   new Net(7).latency(5, 120))) {
            var c = fleet.machine("c", "m5.large", "eu");
            fleet.machine("far", "m5.large", "us").serving(new Reporter());
            fleet.machine("near", "m5.large", "eu").serving(new Reporter());
            var toFar = c.channelTo("far");
            var toNear = c.channelTo("near");
            var req = Chunk.newBuilder().setLines(1).build();
            var farStub = WorkerGrpc.newBlockingStub(toFar);
            var nearStub = WorkerGrpc.newBlockingStub(toNear);
            // Alternating, and after a warm-up, so the first call's compilation
            // does not land on whichever arm happened to go first.
            for (int i = 0; i < 3; i++) {
                c.submit(() -> nearStub.map(req)).get();
                c.submit(() -> farStub.map(req)).get();
            }
            int warm = fleet.telemetry().spans().size();
            for (int i = 0; i < 5; i++) {
                c.submit(() -> nearStub.map(req)).get();
                c.submit(() -> farStub.map(req)).get();
            }
            toFar.shutdownNow(); toNear.shutdownNow();

            var rpcs = fleet.telemetry().spans().stream()
                    .filter(s -> s.kind.equals("rpc") && s.id > warm)
                    .sorted(Comparator.comparingDouble(s -> s.t0)).toList();
            double near = median(rpcs.stream().filter(s -> "near".equals(s.detail.get("to")))
                    .map(losim.trace.Telemetry.Span::grossMs).toList());
            double far = median(rpcs.stream().filter(s -> "far".equals(s.detail.get("to")))
                    .map(losim.trace.Telemetry.Span::grossMs).toList());
            System.out.printf("    same zone %.0f refMs, cross zone %.0f refMs "
                            + "(declared 10 and 240 for the round trip)%n", near, far);
            check(far - near > 200 && far - near < 260, String.format(
                  "a cross-zone call costs its two legs and a same-zone one does not "
                  + "(%.0f refMs apart, against 230 declared)", far - near));
        }

        // --- a deadline is reference time too, or it disagrees with everything else
        try (var fleet = fleet(100, Telemetry.Level.NO_PAYLOAD)) {
            var c = fleet.machine("c", "m5.large", "z");
            fleet.machine("slow", "m5.large", "z").serving(new Costed());   // 500 refMs
            var ch = c.channelTo("slow");
            var req = Chunk.newBuilder().setLines(1).build();
            String tight = c.submit(() -> outcome(() -> WorkerGrpc.newBlockingStub(ch)
                    .withDeadlineAfter(200, TimeUnit.MILLISECONDS).map(req))).get();
            String loose = c.submit(() -> outcome(() -> WorkerGrpc.newBlockingStub(ch)
                    .withDeadlineAfter(900, TimeUnit.MILLISECONDS).map(req))).get();
            ch.shutdownNow();
            System.out.printf("    500 refMs of cost: a 200 refMs deadline gives %s, "
                            + "a 900 refMs one gives %s%n", tight, loose);
            check(tight.equals("DEADLINE_EXCEEDED") && loose.equals("OK"),
                  "deadlines are reference time, so they are divided by k_time like the cost they race");
            check(fleet.telemetry().events().stream().anyMatch(e -> e.kind().equals("rpc_timeout")),
                  "and the one that lost is an rpc_timeout in the trace");
        }

        // --- the three ways nothing answers
        for (String how : new String[]{"partition", "loss", "death"}) {
            try (var fleet = new Fleet(new Telemetry(new Clock(100, Clock.measureCorrection()),
                                                     Telemetry.Level.NO_PAYLOAD),
                                       new Net(7))) {
                var c = fleet.machine("c", "m5.large", "z");
                var s = fleet.machine("s", "m5.large", "z").serving(new Reporter());
                switch (how) {
                    case "partition" -> fleet.net().partition("c", "s");
                    case "loss"      -> fleet.net().loss(1.0);
                    case "death"     -> s.kill("spot reclaim");
                }
                var ch = c.channelTo("s");
                var req = Chunk.newBuilder().setLines(1).build();
                long t0 = System.nanoTime();
                String got = c.submit(() -> outcome(() -> WorkerGrpc.newBlockingStub(ch)
                        .withDeadlineAfter(400, TimeUnit.MILLISECONDS).map(req))).get();
                double realMs = (System.nanoTime() - t0) / 1e6;
                ch.shutdownNow();
                check(got.equals("DEADLINE_EXCEEDED"),
                      how + ": the caller waits out its own deadline and learns nothing more");
                check(realMs > 2.5 && realMs < 200,
                      String.format("  and waits the deadline it declared, compressed (%.1f ms real)",
                                    realMs));
                check(fleet.telemetry().dangling().isEmpty(),
                      "  and the span of a call that went nowhere still closes");
            }
        }
        System.out.println("    a dead machine, a cut link and a lost packet are one event from here,");
        System.out.println("    which is the whole difficulty of the thing being taught.");
        System.out.println();
    }

    static String outcome(Runnable call) {
        try { call.run(); return "OK"; }
        catch (io.grpc.StatusRuntimeException e) { return e.getStatus().getCode().name(); }
    }

    // ---------------------------------------------------------- fault placement

    /**
     * Where a fault actually lands.
     *
     * <p>Nothing in Phase 1 schedules one yet, but the dispatcher is what Phase 2
     * builds on and it is easy to break invisibly: a fault that drifts turns a
     * scenario's lesson into a coin toss, and the drift is only visible if
     * something measures it.
     */
    static void faultPlacement() throws Exception {
        System.out.println("=== faults land where the scenario put them ===");
        var clock = new Clock(1.0, Clock.measureCorrection());
        final int N = 40;
        var err = new double[N];
        var done = new CountDownLatch(N);
        try (var d = new losim.time.Dispatcher(clock)) {
            for (int i = 0; i < N; i++) {
                final int k = i;
                final double at = (k + 1) * 5.0;
                d.at(at, () -> {
                    err[k] = Math.abs(clock.elapsedNs() / 1e6 - at);
                    done.countDown();
                });
            }
            d.start();
            check(done.await(30, TimeUnit.SECONDS), "every scheduled fault fired");
        }
        var sorted = err.clone();
        Arrays.sort(sorted);
        double p50 = sorted[N / 2], p99 = sorted[Math.min(N - 1, (int) (N * 0.99))];
        System.out.printf("    %d faults 5 refMs apart: p50 off by %.3f ms, worst %.3f ms%n",
                N, p50, sorted[N - 1]);
        check(p50 < 0.5, String.format(
              "placement is against the clock, not against the last fault (p50 %.3f ms)", p50));
        System.out.printf("    the tail is the OS descheduling one thread among many, not drift "
                        + "(p99 %.3f ms)%n", p99);
        System.out.println();
    }

    // -------------------------------------------------------------- trace shapes

    static void traceShape() throws Exception {
        System.out.println("=== the trace: three channels, and shapes that are a contract ===");
        try (var fleet = fleet(200, Telemetry.Level.FULL)) {
            var c = fleet.machine("c", "m5.large", "z");
            fleet.machine("s", "m5.large", "z").serving(new Costed());
            fleet.startSampling(2000);
            ManagedChannel ch = c.channelTo("s");
            var stub = WorkerGrpc.newBlockingStub(ch);
            for (int i = 0; i < 4; i++)
                c.submit(() -> stub.map(Chunk.newBuilder().setLines(1).build())).get();
            c.compute("merge", () -> Counts.newBuilder().putCounts("merged", 4).build());
            fleet.stopSampling();
            ch.shutdownNow();

            var trace = Trace.of(fleet.telemetry());
            var shape = trace.shape();
            System.out.println("    event kinds: " + String.join(", ", shape.keySet()));

            check(shape.getOrDefault("rpc_call", Set.of())
                       .containsAll(Set.of("to", "method", "call")),
                  "rpc_call keeps its fields");
            check(shape.getOrDefault("handler_start", Set.of()).containsAll(Set.of("method", "call")),
                  "handler_start keeps its fields");
            check(shape.getOrDefault("handler_end", Set.of())
                       .containsAll(Set.of("method", "call", "status", "ms")),
                  "handler_end keeps its fields, and carries what the program took");
            check(trace.spans().stream().anyMatch(s -> s.kind.equals("compute")),
                  "local computation is telemetrized, so a merging machine is not indistinguishable "
                  + "from an idle one");
            check(!trace.series().isEmpty() && trace.series().containsKey("s.busyPct"),
                  "the series channel carries what every machine held meanwhile ("
                  + trace.series().size() + " channels)");

            String json = trace.toJson();
            check(json.contains("\"spans\"") && json.contains("\"series\"") && json.contains("\"events\""),
                  "and all three reach the wire as separate top-level channels");
            var enc = trace.series().values().stream()
                    .map(Telemetry::encode).toList();
            long constant = enc.stream().filter(e -> e.form().equals("constant")).count();
            System.out.printf("    %d of %d series encoded as a single constant%n", constant, enc.size());
            check(constant > 0, "flat channels cost one number, which is what keeps a dense trace small");
        }
        System.out.println();
    }

    // ----------------------------------------------------- what losim gives back

    /** A handler that reveals a primitive, N times. */
    static final class Primitive extends WorkerBase {
        @Override protected Counts map(Chunk c) {
            for (int i = 0; i < c.getLines(); i++) Losim.current().reveal("n", 1000 + i);
            return Counts.getDefaultInstance();
        }
    }

    /** The same handler, one keyword different — and that keyword is the bug. */
    static final class Boxed extends WorkerBase {
        @Override protected Counts map(Chunk c) {
            for (int i = 0; i < c.getLines(); i++) Losim.current().reveal("n", (Object) (1000 + i));
            return Counts.getDefaultInstance();
        }
    }

    static long revealCost(WorkerBase handler, int reveals) throws Exception {
        try (var fleet = fleet(1000, Telemetry.Level.NO_PAYLOAD)) {
            var c = fleet.machine("c", "m5.large", "z");
            var s = fleet.machine("s", "m5.large", "z").serving(handler);
            var ch = c.channelTo("s");
            var stub = WorkerGrpc.newBlockingStub(ch);
            var req = Chunk.newBuilder().setLines(reveals).build();
            for (int i = 0; i < 3; i++) c.submit(() -> stub.map(req)).get();     // warm
            long before = s.allocatedBytes();
            c.submit(() -> stub.map(req)).get();
            long charged = s.allocatedBytes() - before;
            ch.shutdownNow();
            return charged;
        }
    }

    static void exclusion() throws Exception {
        System.out.println("=== what losim gives back, and what it cannot ===");
        System.out.printf("    a bracket cannot fully see itself: %d ns per metered stop, "
                        + "measured once and charged back%n", losim.res.Meter.UNSEEN_NANOS_PER_REGION);

        final int N = 200_000;
        long prim = revealCost(new Primitive(), N);
        long boxed = revealCost(new Boxed(), N);
        System.out.printf("    %d reveals charged to the program: %d bytes with a primitive, "
                        + "%d with an Object%n", N, prim, boxed);
        check(prim < N,
              String.format("reveal(String,int) charges the program under a byte a call (%.2f)",
                            prim / (double) N));
        check(boxed > prim + N * 8L, String.format(
              "and reveal(String,Object) charges it %.0f bytes a call — the box is built at the "
              + "call site, before any bracket can open, so it is billed to the program",
              (boxed - prim) / (double) N));
        System.out.println("    That is why every overload takes a primitive. It is invisible at one");
        System.out.println("    call per handler, and at a thousand it bends the exponent that ships.");

        // D13 rule 7: asserted, not assumed.
        var off = Load.run(4000, 0, Telemetry.Level.OFF, 5);
        var full = Load.run(4000, 0, Telemetry.Level.FULL, 5);
        double drift = Math.abs(full.retainedBytes() - off.retainedBytes())
                     / (double) off.retainedBytes() * 100;
        System.out.printf("    retained heap with telemetry off %.2f MB, at full detail %.2f MB "
                        + "(%.2f%%)%n", off.retainedBytes() / 1048576.0,
                        full.retainedBytes() / 1048576.0, drift);
        check(drift < 0.5,
              "telemetry's own structures lie outside every machine's boundary, so what a machine "
              + "is holding is the same whether or not anyone is watching");
        System.out.println();
    }

    // ---------------------------------------- the one that decides the phase ships

    /** One ladder, fitted. Returns the exponent for each column. */
    record Ladder(double rawBeta, double repBeta, double keyBeta, double r2, double regions) {}

    static Ladder ladder(int[] sizes, long[] seeds, int reveals) throws Exception {
        var n = new double[sizes.length];
        var raw = new double[sizes.length];
        var rep = new double[sizes.length];
        var keys = new double[sizes.length];
        double regions = 0;
        for (int i = 0; i < sizes.length; i++) {
            var runs = new ArrayList<Load.Result>();
            for (long seed : seeds) runs.add(Load.run(sizes[i], reveals, Telemetry.Level.FULL, seed));
            n[i]    = sizes[i];
            raw[i]  = median(runs.stream().map(r -> r.rawBytes() / 1048576.0).toList());
            rep[i]  = median(runs.stream().map(r -> r.programBytes() / 1048576.0).toList());
            keys[i] = median(runs.stream().map(r -> (double) r.distinctKeys()).toList());
            regions = median(runs.stream().map(r -> (double) r.losimStops()).toList());
        }
        double[] fRep = Fit.power(n, rep);
        return new Ladder(Fit.power(n, raw)[0], fRep[0], Fit.power(n, keys)[0], fRep[1], regions);
    }

    static void observerLaw() throws Exception {
        System.out.println("=== ACCEPTANCE: does watching change what gets extrapolated? ===");
        System.out.println("  A ladder is fitted twice: once with no losim inside the handler at all,");
        System.out.println("  once with a thousand reveal() calls in every one. Asserting the numbers");
        System.out.println("  is not enough — the law is what ships, and the law is what must not move.");
        System.out.println();
        System.out.println("  And 'must not move' needs a scale to be measured against. R2 says how well");
        System.out.println("  a line went through the points it was given; it says nothing about whether");
        System.out.println("  those points would land there again. So each ladder is refitted on");
        System.out.println("  independent seed sets, and the spread of the exponent when NOTHING has");
        System.out.println("  changed is what a bend has to beat to count as real (D6).");

        final int[] SIZES = {1000, 2000, 4000, 8000};
        // Five, not three. The spread below drops the extreme at each end, and with
        // three sets there is nothing left to measure once you have.
        final long[][] SEED_SETS = {{11, 12}, {21, 22}, {31, 32}, {41, 42}, {51, 52}};
        final int HEAVY = 1000;

        // Both paths, at the ladder's top. Warming only the small bare case leaves
        // the first fitted point compiled differently from the rest, and that alone
        // moves the exponent by more than the effect under test.
        for (int w = 0; w < 3; w++) {
            Load.run(1000, 0, Telemetry.Level.FULL, 1);
            Load.run(8000, HEAVY, Telemetry.Level.FULL, 1);
        }

        var byLevel = new LinkedHashMap<Integer, List<Ladder>>();
        System.out.printf("%n  %-14s %13s %13s %11s %11s%n",
                "reveals/call", "raw alloc b", "reported b", "keys b", "regions");
        for (int reveals : new int[]{0, HEAVY}) {
            var ls = new ArrayList<Ladder>();
            for (long[] set : SEED_SETS) ls.add(ladder(SIZES, set, reveals));
            byLevel.put(reveals, ls);
            System.out.printf("  %-14d %13.4f %13.4f %11.4f %11.0f%n", reveals,
                    median(ls.stream().map(Ladder::rawBeta).toList()),
                    median(ls.stream().map(Ladder::repBeta).toList()),
                    median(ls.stream().map(Ladder::keyBeta).toList()),
                    median(ls.stream().map(Ladder::regions).toList()));
        }

        var bare = byLevel.get(0);
        var heavy = byLevel.get(HEAVY);

        double repBend = Math.abs(median(bare.stream().map(Ladder::repBeta).toList())
                                - median(heavy.stream().map(Ladder::repBeta).toList()));
        double rawBend = Math.abs(median(bare.stream().map(Ladder::rawBeta).toList())
                                - median(heavy.stream().map(Ladder::rawBeta).toList()));
        double keyBend = Math.abs(median(bare.stream().map(Ladder::keyBeta).toList())
                                - median(heavy.stream().map(Ladder::keyBeta).toList()));

        // The exponent's own spread across seed sets, at each level; the larger of
        // the two is what a bend at that level has to clear.
        double repWobble = Math.max(spread(bare.stream().map(Ladder::repBeta).toList()),
                                    spread(heavy.stream().map(Ladder::repBeta).toList()));
        double rawWobble = Math.max(spread(bare.stream().map(Ladder::rawBeta).toList()),
                                    spread(heavy.stream().map(Ladder::rawBeta).toList()));

        System.out.printf("%n  %-12s %10s %10s   %s%n", "", "bend", "wobble", "");
        System.out.printf("  %-12s %10.4f %10.4f   %s%n", "reported", repBend, repWobble,
                repBend <= repWobble ? "inside its own noise: no effect to find"
                                     : "OUTSIDE its own noise: a real bias");
        System.out.printf("  %-12s %10.4f %10.4f   %s%n", "raw", rawBend, rawWobble,
                rawBend > rawWobble ? "OUTSIDE its own noise: a real bias"
                                    : "inside its own noise");
        System.out.printf("  %-12s %10.4f %10s   %s%n", "keys", keyBend, "-",
                "the control: nothing losim does can move it");
        System.out.printf("  regions metered %.0f -> %.0f%n",
                median(bare.stream().map(Ladder::regions).toList()),
                median(heavy.stream().map(Ladder::regions).toList()));

        check(keyBend < 0.01,
              "the control holds: what the program computed is identical either way");
        check(median(heavy.stream().map(Ladder::regions).toList())
              > median(bare.stream().map(Ladder::regions).toList()) * 2,
              "heavier losim use is metered as more regions, not estimated as a per-call constant");

        // ---- ACCEPTANCE
        check(repBend <= repWobble, String.format(
              "ACCEPTANCE: a thousand reveals per handler move the memory law by %.4f, which is "
              + "inside the %.4f the exponent moves on its own — there is no effect left to find",
              repBend, repWobble));
        check(rawBend > rawWobble, String.format(
              "ACCEPTANCE: and the unsubtracted law moves by %.4f against a wobble of %.4f — so the "
              + "exclusion is doing the work, rather than there being nothing to exclude",
              rawBend, rawWobble));

        System.out.println();
        System.out.println("  That second check is the one worth keeping. Without it this suite would");
        System.out.println("  pass just as happily on a build where the subtraction did nothing at all.");
        System.out.println();
        System.out.println("  Both are measured against this host's own noise rather than a constant,");
        System.out.println("  which is what lets the same assertion mean the same thing on a laptop,");
        System.out.println("  in the devcontainer and in a two-core Codespace (D10).");
        System.out.println();
    }
}
