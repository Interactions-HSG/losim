import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.util.*;
import java.util.concurrent.*;
import losim.api.Cost;
import losim.api.Losim;
import losim.runtime.Fleet;
import losim.runtime.Machine;
import losim.t.*;
import losim.time.Clock;
import losim.trace.Telemetry;
import losim.trace.Values;

/**
 * A run with something to look at: a master, six workers, one killed mid-flight,
 * one too small to hold its bucket, and a reduce call into the hole that the
 * master waits out and then redoes itself.
 *
 * <p>This exists to be interrogated. It is the fixture the telemetry is held to,
 * and every question in {@link Debugger} is asked of it — because a recorder that
 * is only ever tested on a run where nothing goes wrong is a recorder that has
 * not been tested.
 */
public final class Wordcount {

    static final double K_TIME = 10.0;                 // a ~10 s job in ~1 s of wall clock

    static final String[] CORPUS = {
        "the cat sat on the mat", "the dog sat on the log", "a bird and a cat",
        "the cat and the dog",    "a log and a mat",        "the bird and the cat"
    };

    /** A worker that maps cheaply and reduces by accumulating, which is what kills it. */
    static final class Worker extends WorkerBase {
        private final Map<String, Integer> holding = new ConcurrentHashMap<>();
        private final Map<String, long[]> ballast = new ConcurrentHashMap<>();
        private final Machine self;

        Worker(Machine self) { this.self = self; }

        @Cost(refMs = 15)
        @Override protected Counts map(Chunk c) {
            var out = new HashMap<String, Integer>();
            for (String word : c.getText().split("\\s+")) out.merge(word, 1, Integer::sum);
            Losim.current().records(1);
            Losim.current().reveal("keys", out.size());
            return Counts.newBuilder().putAllCounts(out).build();
        }

        @Cost(refMs = 40)
        @Override protected Counts reduce(Counts c) {
            // Nothing here declares a size. What this costs is whatever the heap
            // walk finds it holding, which is the only honest way to ask.
            c.getCountsMap().forEach((k, v) -> {
                holding.merge(k, v, Integer::sum);
                ballast.computeIfAbsent(k, x -> new long[180_000]);   // the bucket's payload
            });
            self.wroteDisk(holding.size() * 350_000L);
            Losim.current().reveal("distinctKeys", holding.size());
            if (!self.alive())
                throw new IllegalStateException("machine is out of memory");
            return Counts.newBuilder().putAllCounts(holding).build();
        }
    }

    public static Telemetry run() throws Exception {
        var tel = new Telemetry(new Clock(K_TIME, Clock.measureCorrection()));
        try (var fleet = new Fleet(tel)) {
            tel.startSampling(11_000, 900);

            var master = fleet.machine("master", "m5.large", "eu-central-1a");
            var workers = new ArrayList<Machine>();
            for (int i = 0; i < 6; i++) {
                // w2 is deliberately tiny: something has to actually run out of memory.
                var m = fleet.machine("w" + i, "m5.large",
                                      i < 3 ? "eu-central-1a" : "eu-central-1b",
                                      i == 2 ? 4 : 8192, 300_000);
                m.serving(new Worker(m));
                workers.add(m);
            }

            var chans = new LinkedHashMap<String, ManagedChannel>();
            for (Machine w : workers) chans.put(w.name(), master.channelTo(w.name()));

            // --- map, in parallel across the fleet ---------------------------
            var mapped = new ConcurrentHashMap<String, Counts>();
            var mapSpan = tel.open("master", "phase", "map");
            var mapCtx = Context.current().withValue(Telemetry.SPAN, mapSpan);
            var prev = mapCtx.attach();
            var calls = new ArrayList<Future<?>>();
            for (int i = 0; i < 6; i++) {
                final int k = i;
                calls.add(master.submit(() -> {
                    try {
                        var stub = WorkerGrpc.newBlockingStub(chans.get("w" + k))
                                .withDeadlineAfter(3000, TimeUnit.MILLISECONDS);
                        mapped.put("w" + k, stub.map(Chunk.newBuilder()
                                .setText(CORPUS[k]).setLines(1).build()));
                    } catch (StatusRuntimeException e) {
                        tel.event("master", "log", "message",
                                  "map on w" + k + " failed: " + e.getStatus().getCode());
                    }
                }));
            }
            mapCtx.detach(prev);
            for (Future<?> f : calls) f.get(20, TimeUnit.SECONDS);
            tel.close(mapSpan, "OK", "chunks", 6, "result", Values.render(
                    mapped.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, e -> e.getValue().getCountsMap()))));

            // --- a machine dies ----------------------------------------------
            fleet.clock().spend(60);
            workers.get(0).kill("spot reclaimed");

            // --- reduce; w0 is gone, so the master waits it out and redoes it --
            var redSpan = tel.open("master", "phase", "reduce");
            var merged = new HashMap<String, Integer>();
            var redCtx = Context.current().withValue(Telemetry.SPAN, redSpan);
            var prevRed = redCtx.attach();
            for (int i = 0; i < 6; i++) {
                final Counts c = mapped.get("w" + i);
                if (c == null) continue;
                final int k = i;
                master.submit(() -> {
                    try {
                        var stub = WorkerGrpc.newBlockingStub(chans.get("w" + k))
                                .withDeadlineAfter(5000, TimeUnit.MILLISECONDS);
                        stub.reduce(c).getCountsMap()
                                .forEach((key, v) -> merged.merge(key, v, Integer::sum));
                    } catch (StatusRuntimeException e) {
                        tel.event("master", "log", "message", "reducer w" + k + " unreachable ("
                                  + e.getStatus().getCode() + ") — merging locally");
                        master.compute("local merge for w" + k, () -> {
                            fleet.clock().spend(5000);     // the work the dead machine owed
                            c.getCountsMap().forEach((key, v) -> merged.merge(key, v, Integer::sum));
                            return new TreeMap<>(merged);
                        });
                    }
                }).get(30, TimeUnit.SECONDS);
            }
            redCtx.detach(prevRed);
            tel.close(redSpan, "OK", "keys", merged.size(), "result", Values.render(merged));
            tel.event("master", "done", "value", Values.render(merged));

            chans.values().forEach(ManagedChannel::shutdownNow);
            tel.stopSampling();
        }
        return tel;
    }

    public static void main(String[] args) throws Exception {
        var tel = run();
        System.out.printf("events %d   spans %d   series %d   ticks %d   dangling %d%n",
                tel.events().size(), tel.spans().size(), tel.series().size(),
                tel.sampleTimes().length, tel.dangling().size());
    }
}
