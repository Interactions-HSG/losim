import java.nio.file.Path;
import java.util.*;
import losim.runtime.Run;
import losim.scenario.Loader;
import losim.scenario.Scenario;
import losim.scenario.Yaml;
import losim.trace.Telemetry;
import losim.verify.*;

/**
 * Phase 4 — trust markers.
 *
 * <p>losim's numbers mean something only if the code stayed inside the simulated
 * world. Code that reads the real clock, or writes a real file, or hands its work to
 * a thread nobody owns, still runs perfectly well and produces a trace full of
 * plausible figures — and every one of them is wrong in a direction nobody can see.
 *
 * <p>So the tests here are about two things, and the second is the harder one.
 * First, that each of those is actually found, at the line it was written on. Second,
 * that finding it <b>flags rather than refuses</b>: the run happens, the numbers come
 * out, and what carries a caveat says so beside itself. A verifier that stopped the
 * run would be easier to write and would teach that losim is a cage.
 */
public class Phase4 {

    static int pass = 0, fail = 0;

    static void check(boolean ok, String what) {
        System.out.printf("  [%s] %s%n", ok ? "PASS" : "FAIL", what);
        if (ok) pass++; else fail++;
    }

    static final Path CODE = Path.of("build/test-classes");
    static final Set<String> SERVICES = Set.of("Counter", "Peeker", "Napper", "Scribbler",
            "Dialer", "Forker", "Hoarder", "Referrer", "Deferrer", "Threader", "Caller", "Spawner");

    static Verifier verifier() { return Verifier.over(List.of(CODE)); }

    static Verifier.Report look(String... roots) {
        return verifier().from(List.of(roots), SERVICES);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Phase 4 — what makes a number stop meaning what it says\n");
        rules();
        clean();
        generated();
        flagsNotGates();
        attribution();
        undermining();
        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------- every rule, on its line

    static void rules() {
        System.out.println("=== each thing that makes the simulation lie, found where it was written ===");
        trips("Peeker",    Rule.REAL_CLOCK,          Flag.TIMELINE,  "Peeker.java");
        trips("Napper",    Rule.REAL_SLEEP,          Flag.TIMELINE,  "Napper.java");
        trips("Scribbler", Rule.FILE_IO,             Flag.DISK,      "Scribbler.java");
        trips("Dialer",    Rule.OWN_CHANNEL,         Flag.WIRE,      "Dialer.java");
        trips("Forker",    Rule.UNATTRIBUTED_THREAD, Flag.MEMORY,    "Forker.java");
        trips("Threader",  Rule.VIRTUAL_THREAD,      Flag.MEMORY,    "Threader.java");
        trips("Caller",    Rule.OUTSIDE_THE_JVM,     Flag.ISOLATION, "Caller.java");
        trips("Hoarder",   Rule.SHARED_STATE,        Flag.ISOLATION, "Hoarder.java");
        trips("Referrer",  Rule.MACHINES_TOUCHING,   Flag.ISOLATION, "Referrer.java");

        // A class that is itself a thread starts itself by inheritance, so every call
        // site names a lab class and matches nothing. Only the declaration says it.
        var spawner = look("Spawner");
        check(spawner.findings().stream().anyMatch(f -> f.rule() == Rule.UNATTRIBUTED_THREAD
                        && f.what().equals("extends Thread")),
              "a class that extends Thread is found by its declaration — its start() compiles"
              + " to a method on a lab class and matches nothing, so reading the call sites"
              + " alone misses it entirely");

        // The one that is invisible to anything reading the instructions: a method
        // reference names its target in a bootstrap argument and nowhere else.
        var deferrer = look("Deferrer");
        boolean found = deferrer.findings().stream().anyMatch(f -> f.rule() == Rule.REAL_CLOCK);
        check(found, "System::nanoTime is found although it appears in no instruction — a method"
                + " reference lives in a bootstrap argument, so reading the code alone makes"
                + " Deferrer look spotless");

        // A constant is not state, and the difference is what the initialiser did.
        var hoarder = look("Hoarder");
        boolean table = hoarder.findings().stream().anyMatch(f -> f.what().contains("IGNORED"));
        check(!table, "and a static final String[] of literals is not reported: nothing is called"
                + " to build it, which is what separates a table from state — a report that"
                + " flags constants is one people learn to scroll past");
        System.out.println();
    }

    static void trips(String service, Rule expected, Flag flag, String file) {
        var report = look(service);
        var mine = report.findings().stream().filter(f -> f.owner().equals(service)).toList();
        var hit = mine.stream().filter(f -> f.rule() == expected).findFirst();
        boolean located = hit.isPresent() && hit.get().where().startsWith(file)
                       && hit.get().where().contains(":");
        check(located && hit.get().flag() == flag, String.format(
                "%-10s %s%s", service, expected,
                hit.isEmpty() ? "  ->  not found, found " + mine
                              : "  ->  " + hit.get().describe().trim()));
    }

    // ----------------------------------------------------------------- and the rest

    static void clean() {
        System.out.println("=== and nothing at all on code that stayed inside ===");
        var report = look("Counter", "WordCountJob");
        check(report.clean(), "a service and a job that never leave the simulated world are"
                + " reported clean, having walked " + report.walked().size() + " classes: "
                + (report.clean() ? "" : report.findings().toString()));
        check(report.walked().contains("WorkerBase"),
              "and the walk followed them into their own base class rather than stopping at"
              + " what the scenario named — a lie one call deep is still a lie");
        System.out.println();
    }

    /**
     * The claim D11 makes for flagging rather than refusing: generated code needs no
     * special case. Worth testing because it is not obvious — protoc's output trips
     * these rules freely, and a gate would have to argue with every one.
     */
    static void generated() {
        System.out.println("=== generated code is skipped, and that is what flagging buys ===");
        var report = look("Counter");
        check(report.generated().contains("losim.t.Chunk")
              && report.generated().stream().anyMatch(n -> n.startsWith("losim.t.WorkerGrpc")),
              "the walk reaches protoc's output — " + report.generated().size() + " classes,"
              + " recognised by protobuf's superclass and grpc-java's own @GrpcGenerated"
              + " rather than by a guess about their names");

        var owners = report.findings().stream().map(Finding::owner).toList();
        check(report.generated().stream().noneMatch(owners::contains),
              "and not one finding comes from any of them");

        // What the skip is actually worth, rather than assumed to be worth.
        var without = verifier().from(List.of("Counter"), SERVICES, false);
        var fromGenerated = without.findings().stream()
                .filter(f -> report.generated().contains(f.owner())).toList();
        check(!fromGenerated.isEmpty(), String.format(
                "checked anyway, generated code trips %d of these rules — %s — so the skip is"
                + " load-bearing, and a hard gate would have to argue with every one of them",
                fromGenerated.size(), fromGenerated.get(0).describe().trim()));
        System.out.println();
    }

    // --------------------------------------------------------- it runs the code anyway

    static String fleet(String w0, String w1) {
        return """
            seed: 4
            kTime: 4
            job: WordCountJob
            expectedRun: 4 refSeconds
            machines:
              master: { instance: m5.large, zone: z }
              w0: { instance: m5.large, zone: z, serves: [%s] }
              w1: { instance: m5.large, zone: z, serves: [%s] }
            """.formatted(w0, w1);
    }

    static Run.Result run(String yaml) throws Exception {
        Scenario s = Loader.of(Yaml.parse("trust.yaml", yaml));
        return Run.of(s, Phase4.class.getClassLoader(), Telemetry.Level.FULL,
                      Trust.of(s, List.of(CODE)));
    }

    static void flagsNotGates() throws Exception {
        System.out.println("=== a wrong number is not a broken run ===");
        var result = run(fleet("Peeker", "Peeker"));

        check(result.completed(), "a fleet whose workers read the real clock runs to completion"
                + " and answers — nothing was refused, because nothing here is a failure:"
                + " every one of these rules yields a wrong number, not a broken run");
        check(result.peakOf(t -> t.peakRetainedBytes()) > 0 && result.durationRefMs() > 0,
              "it produces the same figures as any other run: a duration, a memory peak,"
              + " byte counts. They are simply no longer what they claim to be");

        var flagged = result.telemetry().events().stream()
                .filter(e -> e.kind().equals("trust")).toList();
        check(flagged.size() == 2 && flagged.stream().allMatch(e -> e.vm().startsWith("w")),
              "and the trace says so on the machines themselves — " + flagged.size()
              + " trust events, on the machines that carry the code, rather than in a log"
              + " somebody has to think to open");

        var detail = flagged.isEmpty() ? Map.<String, Object>of() : flagged.get(0).detail();
        check(String.valueOf(detail.get("flags")).contains("timeline")
              && String.valueOf(detail.get("untrustworthy")).contains("makespanRefMs"),
              "naming the flag and the figure it undermines: " + detail.get("says"));

        String json = result.trace().toJson();
        check(json.contains("\"trusted\":false"),
              "and the trace's own header carries the verdict, so a reader who opens nothing"
              + " else still knows");

        var honest = run(fleet("Counter", "Counter"));
        check(honest.trace().toJson().contains("\"trusted\":true")
              && honest.telemetry().events().stream().noneMatch(e -> e.kind().equals("trust")),
              "while a fleet that stayed inside says trusted, and carries no trust events at"
              + " all — a marker on everything is a marker on nothing");
        System.out.println();
    }

    static void attribution() throws Exception {
        System.out.println("=== flagged per machine, not per fleet ===");
        var result = run(fleet("Scribbler", "Counter"));
        var trust = result.trust();

        check(trust.machines().equals(Set.of("w0")), String.format(
                "w0 serves the class that writes a real file and w1 does not, so w0 is flagged"
                + " and w1 is not — flagged: %s", trust.machines()));
        check(trust.flagsFor("w0").equals(EnumSet.of(Flag.DISK))
              && trust.flagsFor("w1").isEmpty(),
              "w0's disk figure is a lower bound; everything else about w0, and everything"
              + " about w1, still means what it says");

        // The job runs on the first machine in the file, so that is whose counters it
        // lands on and whose figures it can spoil.
        var byJob = Loader.of(Yaml.parse("trust.yaml", """
            seed: 4
            kTime: 4
            job: ClockingJob
            expectedRun: 2 refSeconds
            machines:
              master: { instance: m5.large, zone: z }
              w0: { instance: m5.large, zone: z, serves: [Counter] }
            """));
        var jobTrust = Trust.of(byJob, List.of(CODE));
        check(jobTrust.machines().equals(Set.of("master")),
              "and a job that reads the real clock flags the machine it runs on, which is the"
              + " first in the file — the job is not a machine, but its allocation and its"
              + " wall clock land on one");
        System.out.println();
    }

    static void undermining() throws Exception {
        System.out.println("=== the flag reaches the number ===");
        var trust = run(fleet("Forker", "Counter")).trust();

        check(trust.undermining("memoryMb").equals(List.of("w0"))
              && trust.undermining("allocMb").equals(List.of("w0")),
              "work on a thread the machine did not create is allocation charged to nobody,"
              + " so w0's memory and allocation are lower bounds — and both figures know it");
        check(trust.undermining("wireMb").isEmpty() && trust.undermining("diskMb").isEmpty(),
              "and its wire and disk figures are untouched: a caveat that lands on every"
              + " column is one nobody can act on");

        var everything = run(fleet("Referrer", "Counter")).trust();
        check(everything.undermining("makespanRefMs").equals(List.of("w0"))
              && everything.undermining("memoryMb").equals(List.of("w0")),
              "whereas two machines sharing a field undermines every per-machine figure at"
              + " once, because attributing anything to either of them is then arithmetic"
              + " on a fiction");
        System.out.println();
    }
}
