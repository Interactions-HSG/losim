package losim.cli;

import java.util.ArrayList;
import java.util.List;
import losim.scenario.Loader;
import losim.scenario.Node;
import losim.scenario.Yaml;

/**
 * What an existing scenario looks like to the authoring form.
 *
 * <p>The mirror image of {@link Palette}: that reads a lab's code and says
 * what <i>could</i> be placed; this reads a scenario already on disk and says
 * what <i>was</i> — a pool, an instance, a set of zones, which classes run on
 * it, and the weather — in the same shape the form composes a new one in.
 *
 * <p><b>Parses nothing itself.</b> {@link Yaml#parse} is called once, by
 * {@link Loader#of}, which is asked first: a file that would not load at all
 * fails there, with the loader's own line-numbered refusal, so "this scenario
 * is broken" and "this scenario is valid but the form has no control for
 * something in it" are never confused with each other. What follows is a
 * second walk of the same parsed tree — the relationship {@link Loader}'s own
 * private methods already have to it — not a second grammar.
 *
 * <p><b>Everything the form has no control for is a refusal, not a guess.</b>
 * Anything present here and absent from the Draft model comes back naming the
 * key and the line, never a Draft missing it silently and a save that quietly
 * drops what it never showed.
 *
 * <p>The list is empty. A scenario the console can run but cannot open is a dead
 * end in a course whose interface <i>is</i> the console, so every key
 * {@link Loader} accepts is read back here — every fault kind, the workload, the
 * mode, the per-pool caps, the prefix, the per-machine overrides, the retry
 * multiplier, the tight-margin marker. What is still refused is a file the form
 * could not write <i>back</i> without changing it: a pool of one spelled
 * {@code count: 1}, whose machine is called {@code a0} where the form's would be
 * called {@code a}.
 */
public final class Draft {
    private Draft() {}

    /**
     * One block of machines that grow and shrink together.
     *
     * <p>{@code memoryMb} and {@code diskMb} are caps, and {@code null} is not
     * zero: it means whatever the instance type says. The form needs the three
     * states — inherit, or a number — because a scenario that wrote
     * {@code memoryMb: 0} would be a machine that cannot hold anything.
     */
    public record Pool(String name, int count, String prefix, String instance,
                       List<String> zones, List<String> runs, Double memoryMb, Double diskMb,
                       List<Override> overrides) {}

    /**
     * One machine in a pool, differing from its siblings.
     *
     * <p>Empty and null both mean "the pool's own", because that is what a key
     * the file left out means. A pool of eight where one is half the size is the
     * cheapest way to build a straggler, and a pool where one has a smaller disk
     * is how a scenario shows a machine filling up while its neighbours do not.
     */
    public record Override(String machine, String instance, String zone,
                           Double memoryMb, Double diskMb) {}

    /**
     * One thing that happens at one instant.
     *
     * <p>{@code kind} is any of {@link losim.scenario.Scenario.Kind}, lowercased.
     * Which of the remaining fields means anything depends on it, and only those
     * are written back — a kill's {@code restartAfterRefMs}, a freeze's
     * {@code forRefMs}, a degrade's {@code factor}, a spot reclaim's
     * {@code noticeRefMs}.
     *
     * <p>{@code other} is the second machine, and only {@code partition} and
     * {@code heal} have one. That is the whole of what those two teach:
     * reachability is a property of a <i>pair</i>, not of a machine. Both stay
     * alive, both stay in the registry, both keep serving everyone else, and one
     * caller sees nothing.
     */
    public record Fault(String kind, double atRefMs, String target, String other,
                        double forRefMs, double factor, double noticeRefMs,
                        double restartAfterRefMs) {}

    /**
     * How much work there is at full scale, and the grid the engine probes with.
     *
     * <p>Read back after the loader has filled its own defaults, so what the form
     * shows is what the run will use rather than what the file happened to spell
     * out. Null when the file has no {@code workload:} at all, which is a
     * different thing from one that declares a single record.
     */
    public record Workload(long records, List<Integer> probe, List<Integer> workers) {}

    public record Chaos(String kind, double everyRefMs, String among, double forRefMs, double factor) {}
    /**
     * @param multiplier what the wait is multiplied by after each attempt. 1 is a
     *                   flat backoff; anything above it is exponential, which is
     *                   the difference between a fleet that eases off a struggling
     *                   machine and one that keeps hammering it at a fixed rate.
     */
    public record Retry(String method, int attempts, double backoffRefMs,
                        double multiplier, boolean unsafe) {}

    /** The medium, in the same four numbers {@code network:} is written in. All zero is no key at all. */
    public record Net(double sameZoneRefMs, double crossZoneRefMs, double jitterRefMs, double loss) {}

    public record Of(String name, String job, long seed, double kTime, double expectedRunRefSeconds,
                      boolean tightMargin, String mode, Workload workload,
                      Net net, List<Pool> pools, List<Fault> faults, List<Chaos> chaos,
                      List<Retry> retries) {}

    /**
     * Every fault kind, and for each the keys it does not obey.
     *
     * <p>Read straight off {@code Run.schedule}, which is the only place that
     * decides what a kind actually does with a key. Refused rather than dropped,
     * and refused rather than shown, because every one of them either belongs to
     * a different kind or does nothing at all. {@code for:} on a degrade is the
     * one worth naming: {@link losim.scenario.Loader} accepts it, and
     * {@code Run.schedule} schedules no thaw for a one-time degrade, so the
     * machine stays slow for the rest of the run. A control writing a key the run
     * ignores is a lie told in a form, so the form does not have one and a file
     * using it is sent back.
     *
     * <p>The two pair faults obey nothing but {@code at:} and their own two
     * machines. There is no partition that ends after a while — a {@code heal:}
     * at a later instant is how one ends, and writing both is the exercise.
     */
    private static final java.util.Map<String, List<String>> UNUSABLE = java.util.Map.of(
            "kill",         List.of("notice", "for", "factor"),
            "freeze",       List.of("notice", "factor", "restart_after"),
            "degrade",      List.of("notice", "for", "restart_after"),
            "restart",      List.of("notice", "for", "factor", "restart_after"),
            "spot_reclaim", List.of("for", "factor"),
            "partition",    List.of("notice", "for", "factor", "restart_after"),
            "heal",         List.of("notice", "for", "factor", "restart_after"));

    /** The two whose value is a pair of machines rather than one. */
    private static final List<String> PAIRED = List.of("partition", "heal");

    /**
     * @param name the file's own name, {@code two-machines.yaml} — trimmed to the
     *             stem the form knows it by, since the loader's own comment line
     *             (the only place {@code toYaml} writes a name) is not something
     *             {@link Yaml#parse} keeps; comments are stripped before anything
     *             sees them
     * @param text the file's own content
     * @throws IllegalArgumentException the loader's own refusal, or this one's —
     *         both {@code file:line: message}, indistinguishable to whoever reads it
     */
    public static Of of(String name, String text) {
        Node root = Yaml.parse(name, text);
        var sc = Loader.of(root);   // the real check, first — baseline correctness is never re-done below

        // Nothing is refused at the top level any more. The form has a control
        // for every key `Loader.of` allows there, so `sc.net()`, `sc.mode()`,
        // `sc.workload()` and `sc.tightMargin()` below are read straight off the
        // loader's own already-resolved answer and there is nothing to walk.

        var pools = new ArrayList<Pool>();
        for (var entry : root.at("machines").map().entrySet()) {
            String poolName = entry.getKey();
            Node spec = entry.getValue();
            var overrides = new ArrayList<Override>();
            if (spec.opt("overrides").present()) {
                for (var o : spec.at("overrides").map().entrySet()) {
                    Node body = o.getValue();
                    // The loader checks this only for overrides that name a machine
                    // it expands, and silently ignores one that names nothing. This
                    // has to check every entry, because what it cannot read it
                    // cannot write back, and a save would drop it without saying so.
                    body.onlyAllows("instance", "zone", "memoryMb", "diskMb");
                    overrides.add(new Override(o.getKey(),
                            body.opt("instance").present() ? body.at("instance").str() : "",
                            body.opt("zone").present() ? body.at("zone").str() : "",
                            body.opt("memoryMb").present() ? body.at("memoryMb").num() : null,
                            body.opt("diskMb").present() ? body.at("diskMb").num() : null));
                }
            }
            if (!spec.opt("zone").present()) throw spec.fail(
                    "'" + poolName + "' has no zone: — the form always writes one, so a scenario"
                    + " without one did not come from it. Add a zone: and open it here again.");
            String instance = spec.at("instance").str();
            var zones = spec.at("zone").strings();
            var runs = spec.opt("runs").present() ? spec.at("runs").strings() : List.<String>of();
            Double memoryMb = spec.opt("memoryMb").present() ? spec.at("memoryMb").num() : null;
            Double diskMb = spec.opt("diskMb").present() ? spec.at("diskMb").num() : null;
            int count = spec.opt("count").integer(0);
            if (count == 0) {
                pools.add(new Pool(poolName, 1, poolName, instance, zones, runs,
                        memoryMb, diskMb, List.copyOf(overrides)));
                continue;
            }
            String prefix = spec.opt("prefix").str(poolName);
            // The one shape a pool cannot be written back in. A pool of one with
            // an explicit `count: 1` is called `a0`; the form writes a pool of one
            // without a count, which is called `a`. Same fleet, different machine
            // names, and every fault points at a name.
            if (count == 1 && prefix.equals(poolName)) throw spec.at("count").fail(
                    "'" + poolName + "' is a pool of one written with count: 1, so its machine is"
                    + " called '" + poolName + "0'. The form writes a pool of one without a count,"
                    + " which names it '" + poolName + "' — and a fault pointing at either name"
                    + " would then be pointing at the other. Drop the count:, or give the pool a"
                    + " prefix: of its own, then open it here again.");
            pools.add(new Pool(poolName, count, prefix, instance, zones, runs,
                    memoryMb, diskMb, List.copyOf(overrides)));
        }

        var faults = new ArrayList<Fault>();
        for (Node f : root.opt("faults").list()) {
            String kind = null;
            for (String k : UNUSABLE.keySet()) if (f.opt(k).present()) kind = k;
            // Two kinds at once is already the loader's refusal, and UNUSABLE now
            // names every kind the loader accepts — so this fires only if the
            // loader has grown one this form has not.
            if (kind == null) throw f.fail(
                    "a fault kind this form does not know. It edits "
                    + String.join(", ", UNUSABLE.keySet()) + ". Edit the file directly,"
                    + " then open it here again.");
            for (String key : UNUSABLE.get(kind)) {
                if (f.opt(key).present()) throw f.at(key).fail(
                        "this " + kind + " fault uses " + key + ":, which does nothing to a "
                        + kind + " and which the form has no control for. Edit the file"
                        + " directly, then open it here again.");
            }
            // A pair fault names two machines under one key; every other kind
            // names one. The loader has already checked the count and that both
            // exist, so this only has to read them apart.
            String target, other = "";
            if (PAIRED.contains(kind)) {
                var pair = f.at(kind).strings();
                target = pair.get(0);
                other = pair.get(1);
            } else {
                target = f.at(kind).str();
            }
            // Defaults match the loader's own, so a freeze that omits `for:` reads
            // back as the 1000 refMs it will actually be run with rather than a 0
            // the form would then write down and change the scenario by.
            faults.add(new Fault(kind, f.at("at").refMs(), target, other,
                    f.opt("for").refMs(1000), f.opt("factor").num(2),
                    f.opt("notice").refMs(0), f.opt("restart_after").refMs(0)));
        }

        var chaos = new ArrayList<Chaos>();
        for (Node c : root.opt("chaos").list()) {
            for (var entry : c.map().entrySet()) {
                Node body = entry.getValue();
                chaos.add(new Chaos(entry.getKey(), body.at("every").refMs(), body.at("among").str(),
                        body.opt("for").refMs(1000), body.opt("factor").num(2)));
            }
        }

        var retries = new ArrayList<Retry>();
        for (Node r : root.opt("retries").list()) {
            retries.add(new Retry(r.at("method").str(), r.at("attempts").integer(),
                    r.opt("backoff").refMs(0), r.opt("multiplier").num(1),
                    r.opt("unsafe").bool(false)));
        }

        var net = new Net(sc.net().sameZoneRefMs(), sc.net().crossZoneRefMs(),
                sc.net().jitterRefMs(), sc.net().loss());

        // Null when the file has no `workload:`; otherwise the loader's own
        // resolved answer, defaults filled — a probe ladder the file left unsaid
        // is still the ladder the run climbs, and the form has to show it rather
        // than an empty box that would write a different scenario back.
        var w = sc.workload();
        var workload = w == null ? null
                : new Workload(w.records(), List.copyOf(w.probeSizes()), List.copyOf(w.workerCounts()));

        return new Of(name.replaceAll("\\.ya?ml$", ""), sc.job(), sc.seed(), sc.kTime(),
                sc.expectedRunRefMs() / 1000, sc.tightMargin(), sc.mode().name().toLowerCase(), workload,
                net, List.copyOf(pools), List.copyOf(faults),
                List.copyOf(chaos), List.copyOf(retries));
    }
}
