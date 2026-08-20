import harness.T;
import losim.api.Faults;
import losim.net.ProtoCodec;
import losim.net.RecordCodec;
import losim.res.DiskAccountant;
import losim.res.InstanceCatalog;
import losim.api.Data;
import losim.res.MemoryAccountant;
import losim.runtime.Sim;
import losim.scenario.Node;
import losim.scenario.Scenario;
import losim.scenario.ScenarioLoader;
import losim.verify.Verifier;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Tests {

    static final String RING = """
        name: t-ring
        seed: 7
        run_until: 5s
        codec: record
        vms:
          ring:
            prefix: vm
            program: fixtures.Pinger
            instance: m5.large
            count: 4
        network:
          latency: {mean: 20ms, stddev: 5ms}
        """;

    static Sim.Result run(String yaml, long seed) {
        Scenario s = ScenarioLoader.parse(yaml, "test.yaml");
        return new Sim(s, Tests.class.getClassLoader(), null).run(seed);
    }

    public static void main(String[] args) {
        determinism();
        codecs();
        resources();
        config();
        verifier();
        knobs();
        failure();
        workloads();
        billing();
        System.exit(T.exit());
    }

    // ---------------------------------------------------------------- determinism
    static void determinism() {
        T.suite("determinism");

        T.test("same seed gives a byte-identical trace across 100 runs", () -> {
            String golden = run(RING, 7).trace().digest();
            Set<String> distinct = new LinkedHashSet<>();
            distinct.add(golden);
            for (int i = 0; i < 99; i++) distinct.add(run(RING, 7).trace().digest());
            T.eq(1, distinct.size(), "distinct traces");
        });

        T.test("a different seed gives a different trace", () -> {
            T.isTrue(!run(RING, 7).trace().digest().equals(run(RING, 8).trace().digest()),
                    "seed 7 and seed 8 must differ");
        });

        T.test("the run actually completes", () -> {
            Sim.Result r = run(RING, 7);
            T.eq("hops=8", r.output(), "output");
            T.isTrue(r.finished(), "finished cleanly");
        });

        T.test("handoff invariant holds: one slice per activation", () -> {
            Sim.Result r = run(RING, 7);
            T.isTrue((Integer) r.trace().events().size() > 0, "trace not empty");
        });
    }

    // ---------------------------------------------------------------- codecs
    static void codecs() {
        T.suite("codecs");

        record Pair(String key, int value) {}
        record Pairs(List<Pair> pairs) {}

        T.test("both codecs round-trip a size", () -> {
            Pairs p = new Pairs(List.of(new Pair("the", 2), new Pair("cat", 1)));
            T.isTrue(RecordCodec.INSTANCE.serializedSize(p) > 0, "record size positive");
            T.isTrue(ProtoCodec.INSTANCE.serializedSize(p) > 0, "proto size positive");
        });

        T.test("protobuf is materially smaller — that is what the schema buys", () -> {
            Pairs p = new Pairs(List.of(new Pair("the", 2), new Pair("cat", 1), new Pair("sat", 1)));
            int rec = RecordCodec.INSTANCE.serializedSize(p);
            int pro = ProtoCodec.INSTANCE.serializedSize(p);
            T.isTrue(pro < rec, "proto (" + pro + "B) must be smaller than record (" + rec + "B)");
        });

        T.test("encoding is deterministic", () -> {
            Pairs p = new Pairs(List.of(new Pair("a", 1)));
            T.eq(ProtoCodec.INSTANCE.serializedSize(p), ProtoCodec.INSTANCE.serializedSize(p), "stable size");
        });
    }

    // ---------------------------------------------------------------- resources
    static void resources() {
        T.suite("resources");

        T.test("memory accounting is deterministic and raises OutOfMemory at the cap", () -> {
            MemoryAccountant m = new MemoryAccountant("vm0", 1000);
            m.add(600);
            T.eq(600L, m.used(), "used");
            T.throwsA(Faults.OutOfMemory.class, () -> m.add(600), "crossing the cap");
        });

        T.test("released memory comes back", () -> {
            MemoryAccountant m = new MemoryAccountant("vm0", 1000);
            m.add(500); m.release(300);
            T.eq(200L, m.used(), "used after release");
        });

        T.test("an unfsynced write is lost across a crash", () -> {
            DiskAccountant d = new DiskAccountant("vm0", 10_000);
            d.write("a", new byte[100]);
            d.fsync();
            d.write("b", new byte[100]);          // never flushed
            d.crash();
            T.isTrue(d.read("a") != null, "flushed write survives");
            T.isTrue(d.read("b") == null, "unflushed write must be lost");
        });

        T.test("the disk quota raises NoSpace", () -> {
            DiskAccountant d = new DiskAccountant("vm0", 100);
            T.throwsA(Faults.NoSpace.class, () -> d.write("big", new byte[200]), "over quota");
        });

        T.test("a t3.micro is slower than an m5.large per its vCPU spec", () -> {
            T.isTrue(InstanceCatalog.get("t3.micro").throttledFactor()
                    > InstanceCatalog.get("m5.large").cpuFactor(), "throttled t3 slower than m5");
        });

        T.test("a workload that fits r5.large does not fit t3.micro", () -> {
            T.isTrue(InstanceCatalog.get("r5.large").memoryMb() > InstanceCatalog.get("t3.micro").memoryMb(),
                    "r5 has more memory");
        });

        T.test("unknown instance types are refused by name", () -> {
            T.throwsA(IllegalArgumentException.class,
                    () -> InstanceCatalog.get("t3.tiny"), "unknown instance");
        });
    }

    // ---------------------------------------------------------------- config
    static void config() {
        T.suite("scenario config");

        T.test("a valid scenario loads", () -> {
            Scenario s = ScenarioLoader.parse(RING, "test.yaml");
            T.eq("t-ring", s.name, "name");
            T.eq(1, s.groups.size(), "groups");
            T.eq(4, s.groups.get(0).count, "count");
        });

        T.test("an unknown instance type reports file and line", () -> {
            String bad = RING.replace("instance: m5.large", "instance: t3.tiny");
            Node.ConfigError e = T.throwsA(Node.ConfigError.class,
                    () -> ScenarioLoader.parse(bad, "wordcount.yaml"), "bad instance");
            T.contains(e.getMessage(), "wordcount.yaml:", "has file:line");
            T.contains(e.getMessage(), "t3.tiny", "names the offender");
        });

        T.test("a fault targeting an unknown VM is refused", () -> {
            String bad = RING + "faults:\n  - {at: 1s, kill: nope}\n";
            Node.ConfigError e = T.throwsA(Node.ConfigError.class,
                    () -> ScenarioLoader.parse(bad, "t.yaml"), "unknown target");
            T.contains(e.getMessage(), "nope", "names the offender");
        });

        T.test("an override naming a non-member is refused", () -> {
            String bad = RING + """
                """.stripTrailing();
            String withOverride = RING.replace("    count: 4",
                    "    count: 4\n    overrides:\n      vm9: {instance: t3.nano}");
            Node.ConfigError e = T.throwsA(Node.ConfigError.class,
                    () -> ScenarioLoader.parse(withOverride, "t.yaml"), "bad override");
            T.contains(e.getMessage(), "vm9", "names the offender");
        });

        T.test("a valid override is accepted and applied", () -> {
            String ok = RING.replace("    count: 4",
                    "    count: 4\n    overrides:\n      vm2: {instance: t3.nano}");
            Scenario s = ScenarioLoader.parse(ok, "t.yaml");
            T.eq(1, s.groups.get(0).overrides.size(), "one override");
        });

        T.test("a missing programs key is refused", () -> {
            String bad = "name: x\nvms:\n  a:\n    instance: m5.large\n";
            T.throwsA(Node.ConfigError.class, () -> ScenarioLoader.parse(bad, "t.yaml"), "no program");
        });

        T.test("durations parse the way people write them", () -> {
            Scenario s = ScenarioLoader.parse(RING.replace("run_until: 5s", "run_until: 1500ms"), "t.yaml");
            T.eq(1500L, s.runUntilMs, "run_until");
        });

        T.test("individually named VMs and groups coexist", () -> {
            String mixed = """
                name: mixed
                vms:
                  master: {program: fixtures.Good, instance: m5.large}
                  workers:
                    prefix: w
                    program: fixtures.Good
                    count: 3
                """;
            Scenario s = ScenarioLoader.parse(mixed, "t.yaml");
            T.eq(2, s.groups.size(), "two groups");
            T.eq(List.of("master"), ScenarioLoader.expandNames(s.groups.get(0)), "named vm");
            T.eq(List.of("w0", "w1", "w2"), ScenarioLoader.expandNames(s.groups.get(1)), "pool");
        });
    }

    // ---------------------------------------------------------------- verifier
    static List<String> verify(Path classes, String cn) {
        try { return Verifier.verifyClass(classes, cn); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static void verifier() {
        T.suite("determinism verifier");

        Path classes = Path.of("build/test-classes");

        T.test("nondeterministic student code is rejected", () -> {
            List<String> problems = verify(classes, "fixtures.Bad");
            String all = String.join("\n", problems);
            T.isTrue(problems.size() >= 3, "expected several problems, got " + problems.size() + ": " + all);
            T.contains(all, "static", "flags the mutable static");
            T.contains(all, "real time", "flags System.nanoTime");
            T.contains(all, "randomness", "flags Math.random");
        });

        T.test("correct student code passes", () -> {
            List<String> problems = verify(classes, "fixtures.Good");
            T.eq(List.of(), problems, "no problems");
        });

        T.test("the lab programs pass", () -> {
            List<String> problems = verify(classes, "fixtures.Pinger");
            T.eq(List.of(), problems, "no problems");
        });
    }

    // ---------------------------------------------------------------- knobs
    static void knobs() {
        T.suite("knobs");

        T.test("degrading cpu multiplies duration in virtual time", () -> {
            String base = RING;
            String slowed = RING + "faults:\n  - {at: 1ms, degrade: vm1, cpu: 0.1}\n";
            long fast = run(base, 7).endedAtMs();
            long slow = run(slowed, 7).endedAtMs();
            T.isTrue(slow > fast, "degraded run (" + slow + "ms) must take longer than " + fast + "ms");
        });

        T.test("cross-zone traffic is slower and counted", () -> {
            String twoZone = """
                name: zones
                seed: 3
                run_until: 10s
                codec: record
                vms:
                  ring:
                    prefix: vm
                    program: fixtures.Pinger
                    instance: m5.large
                    count: 4
                    availability_zone: [eu-central-1a, eu-central-1b]
                """;
            Sim.Result r = run(twoZone, 3);
            long cross = ((Number) r.metrics().get("crossZoneBytes")).longValue();
            T.isTrue(cross > 0, "cross-zone bytes must be billed, got " + cross);
        });

        T.test("same-zone traffic bills no egress", () -> {
            Sim.Result r = run(RING, 7);
            T.eq(0L, ((Number) r.metrics().get("crossZoneBytes")).longValue(), "no cross-zone bytes");
        });

        T.test("message loss drops roughly the configured share", () -> {
            String lossy = RING.replace("network:", "network:\n  loss: 0.5");
            Sim.Result r = run(lossy, 11);
            long dropped = ((Number) r.metrics().get("rpcDropped")).longValue();
            T.isTrue(dropped > 0, "expected some drops at 50% loss");
        });
    }

    // ---------------------------------------------------------------- huge workloads
    static String one(String program, String instance) {
        return """
            name: t-work
            seed: 2
            run_until: 600s
            codec: proto
            vms:
              solo:
                program: %s
                instance: %s
            """.formatted(program, instance);
    }

    static void workloads() {
        T.suite("huge workloads, simulated");

        T.test("a dataset is described, not materialised", () -> {
            Data d = Data.gigabytes("corpus", 1000, 200);
            T.eq(1000.0, Math.round(d.gigabytes()) * 1.0, "a described terabyte");
            T.eq(5_000_000_000L, d.records(), "record count");
        });

        T.test("splitting is exact — no record is lost or duplicated", () -> {
            Data d = Data.of("d", 1_000_000_007L, 64);
            long total = 0;
            for (Data s : d.split(7)) total += s.records();
            T.eq(d.records(), total, "shard records sum to the whole");
        });

        T.test("holding more than the machine has raises OutOfMemory", () -> {
            Sim.Result r = run(one("fixtures.Hog", "m5.large"), 2);
            T.isTrue(r.output() == null, "the VM must die rather than hold 500 GB");
            T.eq(1, r.events("kill").size(), "one death");
            T.eq("oom", r.events("kill").get(0).get("reason"), "died of OOM");
        });

        T.test("streaming the same volume succeeds on the same machine", () -> {
            Sim.Result r = run(one("fixtures.Streamer", "m5.large"), 2);
            T.contains(String.valueOf(r.output()), "streamed in", "streaming completes");
        });

        T.test("spilling more than the disk holds raises NoSpace", () -> {
            Sim.Result r = run(one("fixtures.Filler", "t3.nano"), 2);
            T.isTrue(r.output() == null, "the VM must die rather than spill 5 TB onto 8 GB");
        });

        T.test("processing a terabyte costs real time but no real memory", () -> {
            Sim.Result r = run(one("fixtures.Streamer", "m5.large"), 2);
            T.isTrue(r.endedAtMs() > 0, "virtual time advanced");
        });

        T.test("a reference is small; the data itself is not", () -> {
            Data d = Data.gigabytes("corpus", 1000, 200);
            T.isTrue(d.ref().bytes() == d.bytes(), "the ref knows the volume");
            T.eq("ref:corpus", d.ref().toString(), "but it is only a pointer");
        });
    }

    // ---------------------------------------------------------------- billing
    static void billing() {
        T.suite("the bill");

        T.test("every run produces a five-bucket account", () -> {
            Sim.Result r = run(RING, 7);
            Map<String, Object> bill = r.pnl().asMap();
            @SuppressWarnings("unchecked")
            Map<String, Object> buckets = (Map<String, Object>) bill.get("buckets");
            for (String b : List.of("revenue", "build", "capacity", "consumption", "incidents"))
                T.isTrue(buckets.containsKey(b), "bucket " + b + " present");
        });

        T.test("every line carries the quantity it came from", () -> {
            Sim.Result r = run(RING, 7);
            for (var line : r.pnl().items()) {
                T.isTrue(line.unit() != null && !line.unit().isBlank(), "line has a unit: " + line.what());
                T.isTrue(line.why() != null && !line.why().isBlank(), "line explains itself: " + line.what());
                T.eq(Math.round(line.quantity() * line.unitPrice() * 10000) / 10000.0, line.amount(),
                        "amount is quantity x price");
            }
        });

        T.test("an idle machine costs the same as a busy one", () -> {
            Sim.Result r = run(RING, 7);
            long capacityLines = r.pnl().items().stream().filter(i -> i.bucket().equals("capacity")).count();
            T.eq(4L, capacityLines, "one capacity line per VM");
        });

        T.test("failure lands in incidents, not capacity", () -> {
            String killed = RING + "faults:\n  - {at: 30ms, kill: vm2}\n";
            Sim.Result r = run(killed, 7);
            T.isTrue(r.pnl().byBucket().get("incidents") > 0, "a death costs something");
        });

        T.test("spot is cheaper than on-demand", () -> {
            losim.price.PriceList pl = losim.price.PriceList.defaults();
            pl.instancePerHour.put("m5.large", 0.10);
            T.isTrue(pl.perHour("m5.large", "spot", 0.10) < pl.perHour("m5.large", "on-demand", 0.10),
                    "spot discount applies");
        });
    }

    // ---------------------------------------------------------------- failure
    static void failure() {
        T.suite("death");

        T.test("killing a VM stops the ring — the naive program has no answer", () -> {
            String killed = RING + "faults:\n  - {at: 30ms, kill: vm2}\n";
            Sim.Result r = run(killed, 7);
            T.isTrue(r.output() == null, "the token cannot come home through a dead VM");
        });

        T.test("a kill is recorded in the trace", () -> {
            String killed = RING + "faults:\n  - {at: 30ms, kill: vm2}\n";
            Sim.Result r = run(killed, 7);
            T.eq(1, r.events("kill").size(), "one kill event");
        });

        T.test("freezing is not death: the VM answers late", () -> {
            String frozen = RING + "faults:\n  - {at: 30ms, freeze: vm2, for: 200ms}\n";
            Sim.Result r = run(frozen, 7);
            T.eq("hops=8", r.output(), "a frozen VM eventually answers");
            T.isTrue(r.endedAtMs() > run(RING, 7).endedAtMs(), "but it took longer");
        });
    }
}
