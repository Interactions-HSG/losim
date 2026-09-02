import io.grpc.ManagedChannel;
import java.util.*;
import java.util.concurrent.*;
import losim.api.Takes;
import losim.api.Losim;
import losim.runtime.Fleet;
import losim.runtime.Machine;
import losim.t.*;
import losim.trace.Telemetry;
import losim.time.Clock;

/**
 * One word count over real gRPC, at a chosen size and a chosen amount of losim.
 *
 * <p>The corpus is drawn from a Zipf distribution on purpose. The claim under
 * test is that different resources scale with different exponents, and that can
 * only be checked against a workload where it is actually true: Zipf text gives
 * Heaps' law, so vocabulary grows sublinearly with length. Uniformly random words
 * would give an exponent of 1 for everything and prove nothing.
 */
public final class Load {

    static final int LINES_PER_CHUNK = 100;
    static final int WORDS_PER_LINE = 8;
    static final int WORKERS = 4;
    static final double K_TIME = 200.0;

    public record Result(long programBytes, long rawBytes, long losimBytes, long losimStops,
                         long wireBytes, long handled, int distinctKeys, long retainedBytes,
                         Telemetry tel) {}

    /** A worker that accumulates, so what it holds grows with the keys it has seen. */
    static final class Counter extends WorkerBase {
        private final Map<String, int[]> held = new ConcurrentHashMap<>();
        private final int reveals;

        Counter(int reveals) { this.reveals = reveals; }

        Map<String, int[]> held() { return held; }

        @Takes(refMs = 0)
        @Override protected Counts map(Chunk c) {
            var out = new HashMap<String, Integer>();
            int lines = 0;
            for (String line : c.getText().split("\n")) {
                lines++;
                for (String w : line.split(" ")) {
                    out.merge(w, 1, Integer::sum);
                    held.computeIfAbsent(w, k -> new int[1])[0]++;
                }
            }
            Losim.current().records(lines);
            // The whole point of the extreme case: at one call per handler an
            // accounting leak is undetectable, and at a thousand it halves the
            // fitted exponent.
            for (int i = 0; i < reveals; i++) Losim.current().reveal("emitted", out.size());
            return Counts.newBuilder().putAllCounts(out).build();
        }
    }

    public static Result run(int records, int reveals, Telemetry.Level level, long seed)
            throws Exception {
        var clock = new Clock(K_TIME, 1.2832);
        var tel = new Telemetry(clock, level);
        try (var fleet = new Fleet(tel)) {
            var master = fleet.machine("master", "m5.2xlarge", "z");
            var workers = new ArrayList<Machine>();
            var counters = new ArrayList<Counter>();
            for (int i = 0; i < WORKERS; i++) {
                var c = new Counter(reveals);
                counters.add(c);
                workers.add(fleet.machine("w" + i, "m5.large", "z").serving(c));
            }

            var channels = new ArrayList<ManagedChannel>();
            var stubs = new ArrayList<WorkerGrpc.WorkerBlockingStub>();
            for (var w : workers) {
                var ch = master.channelTo(w.name());
                channels.add(ch);
                stubs.add(WorkerGrpc.newBlockingStub(ch));
            }

            // Built on the test's own thread, deliberately: the corpus is the
            // fixture, not the workload, and must not land on a machine's ledger.
            var corpus = new Corpus(200_000, 1.1, seed);
            var lines = corpus.lines(records, WORDS_PER_LINE);

            var futures = new ArrayList<Future<?>>();
            for (int off = 0, k = 0; off < lines.size(); off += LINES_PER_CHUNK, k++) {
                String text = String.join("\n", lines.subList(off,
                        Math.min(lines.size(), off + LINES_PER_CHUNK)));
                var stub = stubs.get(k % WORKERS);
                int nLines = Math.min(LINES_PER_CHUNK, lines.size() - off);
                // Driven from the master's own threads, so the client side of
                // every call is attributed to the machine that made it.
                futures.add(master.submit(() -> stub.map(
                        Chunk.newBuilder().setText(text).setLines(nLines).build())));
            }
            for (Future<?> f : futures) f.get(120, TimeUnit.SECONDS);

            long program = 0, raw = 0, lb = 0, lr = 0, wire = 0, handled = 0, retained = 0;
            var keys = new HashSet<String>();
            for (int i = 0; i < workers.size(); i++) {
                var w = workers.get(i);
                program += w.allocatedBytes();
                raw     += w.rawAllocatedBytes();
                lb      += w.losimBytes();
                lr      += w.losimStops();
                wire    += w.bytesOut();
                handled += w.handledCalls();
                retained += w.measureRetained().bytes();
                keys.addAll(counters.get(i).held().keySet());
            }
            channels.forEach(ManagedChannel::shutdownNow);
            return new Result(program, raw, lb, lr, wire, handled, keys.size(), retained, tel);
        }
    }
}
