package losim.cli;

import java.util.ArrayList;
import java.util.List;
import losim.sim.Loader;
import losim.sim.Field;
import losim.sim.Yaml;

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
 * key and the line, never a Draft missing it silently and a save that drops
 * what it never showed.
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
     * <p>{@code memoryMb} and {@code diskMb} are caps, with three states: no
     * value inherits the instance type's own, a number overrides it, and 0
     * means a machine that cannot hold anything.
     */
    public record Pool(String name, int count, String prefix, String instance,
                       List<String> zones, java.util.Map<String, Runs> runs,
                       List<Failure> failures,
                       Double memoryMb, Double diskMb, List<Override> overrides) {}

    /**
     * One service a node runs, and what goes wrong with it there.
     *
     * <p>Two levels in one record because the file has two: a service is a file
     * that implements it, and a service on a bad node is a file plus a list of
     * rpcs that misbehave. The form draws the second only when there is one, the
     * way the file writes the longhand only when there is one.
     */
    public record Runs(String file, java.util.Map<String, List<RpcFailure>> failures) {}

    /**
     * One machine in a pool, differing from its siblings.
     *
     * <p>An empty string or a null number falls back to the pool's own value,
     * because that is what a key the file left out means. A pool of eight where
     * one is half the size is the cheapest way to build a straggler; a pool
     * where one has a smaller disk shows a machine filling up while its
     * neighbours do not.
     */
    public record Override(String machine, String instance, String zone,
                           Double memoryMb, Double diskMb, List<Failure> failures) {}

    /**
     * One thing that happens to the node it is written in.
     *
     * <p>{@code kind} is any of {@link losim.sim.Simulation.Kind}, spelled the
     * way the file spells it. Which of the remaining fields means anything depends
     * on it, and only those are written back — a kill's {@code restartAfterRefMs},
     * a freeze's {@code forRefMs}, a degrade's {@code factor}, a spot reclaim's
     * {@code noticeRefMs}. The loader refuses the rest, so the form has no control
     * to draw for them.
     *
     * <p>Exactly one of {@code atRefMs} and {@code perRefMs} is above zero.
     *
     * <p>{@code other} is the far end, and only {@code partition} and {@code heal}
     * have one. That is the whole of what those two teach: reachability is a
     * property of a <i>pair</i>, not of a node. Both stay alive, both stay in the
     * registry, both keep serving everyone else, and one caller sees nothing.
     */
    public record Failure(String kind, double atRefMs, double perRefMs, String other,
                          double forRefMs, double factor, double noticeRefMs,
                          double restartAfterRefMs) {}

    /**
     * One thing that happens to one rpc on one node, one call in {@code perCalls}.
     *
     * <p>{@code status} is a gRPC code name and empty for the other two kinds;
     * {@code factor} means something only to {@code slow}.
     */
    public record RpcFailure(String kind, String status, double factor, int perCalls) {}

    /**
     * @param multiplier what the wait is multiplied by after each attempt. 1 is a
     *                   flat backoff; anything above it is exponential, which is
     *                   the difference between a cluster that eases off a struggling
     *                   machine and one that keeps hammering it at a fixed rate.
     */
    public record Retry(String method, int attempts, double backoffRefMs,
                        double multiplier, boolean unsafe) {}

    /** The medium, in the same four numbers {@code network:} is written in. All zero is no key at all. */
    public record Net(double sameZoneRefMs, double crossZoneRefMs, double jitterRefMs, double loss) {}

    /**
     * What one rpc costs, under the class that serves it.
     *
     * <p>Flat here, one row per rpc, because that is what the form draws: the
     * file's two levels are a heading and its rows, and a form with a heading
     * control would be a form where the heading can be edited into naming
     * nothing.
     */
    public record Duration(String runs, String rpc, double fixedRefMs, double perUnitRefMs) {}

    /**
     * The workload, at full size.
     *
     * <p>Three controls, and the form can draw all three, which is why the parts
     * list it replaced is gone: those were named by a Java class's own
     * declaration, so the form had to ask the code what fields to draw before it
     * could draw any.
     *
     * @param source empty when the simulation named none, which is a workload
     *               generated from the seed rather than read
     */
    public record Workload(String source, String unit, long count) {}

    public record Of(String name, long seed, double scale, String mode,
                      Net net, List<Pool> pools,
                      List<Retry> retries, List<Duration> simulatedDuration, Workload input) {}

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
        Field root = Yaml.parse(name, text);
        var sc = Loader.of(root);   // the real check, first — baseline correctness is never re-done below

        // The form has a control for every key `Loader.of` allows at the top
        // level, so nothing there is refused: `sc.net()`, `sc.mode()`,
        // `sc.workload()` and `sc.tightMargin()` below are read straight off the
        // loader's own already-resolved answer and there is nothing to walk.

        var pools = new ArrayList<Pool>();
        for (var entry : root.at("nodes").map().entrySet()) {
            String poolName = entry.getKey();
            Field spec = entry.getValue();
            var overrides = new ArrayList<Override>();
            if (spec.opt("overrides").present()) {
                for (var o : spec.at("overrides").map().entrySet()) {
                    Field body = o.getValue();
                    // The loader checks this only for overrides that name a machine
                    // it expands, and silently ignores one that names nothing. This
                    // has to check every entry, because what it cannot read it
                    // cannot write back, and a save would drop it without saying so.
                    body.onlyAllows("instance", "zone", "memoryMb", "diskMb", "failures");
                    overrides.add(new Override(o.getKey(),
                            body.opt("instance").present() ? body.at("instance").str() : "",
                            body.opt("zone").present() ? body.at("zone").str() : "",
                            body.opt("memoryMb").present() ? body.at("memoryMb").num() : null,
                            body.opt("diskMb").present() ? body.at("diskMb").num() : null,
                            failures(body.opt("failures"))));
                }
            }
            if (!spec.opt("zone").present()) throw spec.fail(
                    "'" + poolName + "' has no zone: — the form always writes one, so a scenario"
                    + " without one did not come from it. Add a zone: and open it here again.");
            String instance = spec.at("instance").str();
            var zones = spec.at("zone").strings();
            // Service to .java path, the same pair the file writes: the form draws two
            // columns because the file has two halves, and a form that drew one would
            // have to invent the other on save.
            var runs = new java.util.LinkedHashMap<String, Runs>();
            if (spec.opt("runs").present())
                for (var r : spec.at("runs").map().entrySet()) {
                    Field body = r.getValue();
                    boolean longhand = body.isMap();
                    runs.put(r.getKey(), new Runs(
                            (longhand ? body.at("file") : body).str().trim(),
                            longhand ? rpcFailures(body.opt("failures"))
                                     : java.util.Map.of()));
                }
            Double memoryMb = spec.opt("memoryMb").present() ? spec.at("memoryMb").num() : null;
            Double diskMb = spec.opt("diskMb").present() ? spec.at("diskMb").num() : null;
            int count = spec.opt("count").integer(0);
            var failures = failures(spec.opt("failures"));
            if (count == 0) {
                pools.add(new Pool(poolName, 1, poolName, instance, zones, runs, failures,
                        memoryMb, diskMb, List.copyOf(overrides)));
                continue;
            }
            String prefix = spec.opt("prefix").str(poolName);
            // The one shape a pool cannot be written back in. A pool of one with
            // an explicit `count: 1` is called `a0`; the form writes a pool of one
            // without a count, which is called `a`. Same cluster, different machine
            // names, and every fault points at a name.
            if (count == 1 && prefix.equals(poolName)) throw spec.at("count").fail(
                    "'" + poolName + "' is a pool of one written with count: 1, so its machine is"
                    + " called '" + poolName + "0'. The form writes a pool of one without a count,"
                    + " which names it '" + poolName + "' — and a fault pointing at either name"
                    + " would then be pointing at the other. Drop the count:, or give the pool a"
                    + " prefix: of its own, then open it here again.");
            pools.add(new Pool(poolName, count, prefix, instance, zones, runs, failures,
                    memoryMb, diskMb, List.copyOf(overrides)));
        }

        var retries = new ArrayList<Retry>();
        for (Field r : root.opt("retries").list()) {
            retries.add(new Retry(r.at("method").str(), r.at("attempts").integer(),
                    r.opt("backoff").refMs(0), r.opt("multiplier").num(1),
                    r.opt("unsafe").bool(false)));
        }

        var durations = new ArrayList<Duration>();
        // Absent is not empty to `map()`, which refuses anything that is not a
        // block — and a scenario with no costs in it is the ordinary case.
        Field priced = root.opt("simulatedDuration");
        for (var runs : (priced.present() ? priced.map() : java.util.Map.<String, Field>of()).entrySet()) {
            for (var rpc : runs.getValue().map().entrySet()) {
                Field body = rpc.getValue();
                durations.add(new Duration(runs.getKey(), rpc.getKey(),
                        body.opt("fixed").refMs(0), body.opt("perUnit").refMs(0)));
            }
        }

        // Straight off the loader's answer, which has already checked the source,
        // the shape and the sign; there is nothing here the form could ask a better
        // question about.
        var input = new Workload(sc.input().source() == null ? "" : sc.input().source(),
                sc.input().unit(), sc.input().count());

        var net = new Net(sc.net().sameZoneRefMs(), sc.net().crossZoneRefMs(),
                sc.net().jitterRefMs(), sc.net().loss());

        return new Of(name.replaceAll("\\.ya?ml$", ""), sc.seed(), sc.scale(),
                sc.mode().name().toLowerCase(),
                net, List.copyOf(pools),
                List.copyOf(retries), List.copyOf(durations), input);
    }

    /**
     * What happens to a node, read back in the shape the form draws it.
     *
     * <p>Nothing is checked here. The loader has already refused a kind this file
     * does not know, two kinds in one entry, both an {@code at:} and a
     * {@code per:}, neither, a rate on a kind that happens once, and every key a
     * kind ignores — so what reaches this walk is a list the form can draw a row
     * for. The table of which key belongs to which kind used to live here too, and
     * two copies of it were two things to keep in step.
     */
    private static List<Failure> failures(Field node) {
        var out = new ArrayList<Failure>();
        for (Field f : node.list()) {
            String kind = null;
            for (var k : losim.sim.Simulation.Kind.values())
                if (f.opt(k.key()).present()) kind = k.key();
            String other = "";
            double factor = 2;
            if (kind.equals("partition") || kind.equals("heal")) other = f.at(kind).str().trim();
            else if (kind.equals("degrade")) factor = f.at(kind).num();
            // Defaults match the loader's own, so a freeze that omits `for:` reads
            // back as the 1000 refMs it will actually be run with rather than a 0
            // the form would then write down and change the simulation by.
            out.add(new Failure(kind, f.opt("at").refMs(0), f.opt("per").refMs(0), other,
                    f.opt("for").refMs(kind.equals("freeze") ? 1000 : 0), factor,
                    f.opt("notice").refMs(0), f.opt("restartAfter").refMs(0)));
        }
        return out;
    }

    /** What happens to one service's rpcs on one node, keyed by rpc. */
    private static java.util.Map<String, List<RpcFailure>> rpcFailures(Field node) {
        var out = new java.util.LinkedHashMap<String, List<RpcFailure>>();
        if (!node.present()) return out;
        for (var e : node.map().entrySet()) {
            var here = new ArrayList<RpcFailure>();
            for (Field f : e.getValue().list()) {
                String kind = f.opt("status").present() ? "status"
                            : f.opt("slow").present() ? "slow" : "drop";
                here.add(new RpcFailure(kind,
                        kind.equals("status") ? f.at("status").str().trim() : "",
                        kind.equals("slow") ? f.at("slow").num() : 1,
                        f.at("per").calls()));
            }
            out.put(e.getKey(), List.copyOf(here));
        }
        return out;
    }
}
