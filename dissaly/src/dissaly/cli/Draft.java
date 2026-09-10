package dissaly.cli;

import java.util.ArrayList;
import java.util.List;
import dissaly.sim.Loader;
import dissaly.sim.Field;
import dissaly.sim.Yaml;

/**
 * Parsed simulation data exposed to the authoring form.
 *
 * <p>{@link Palette} describes values that can be added to a project. This
 * class describes values already present in a simulation, using the form's
 * model.
 *
 * <p>{@link Yaml#parse} and {@link Loader#of} perform parsing and validation
 * first. The remaining walk reads the validated tree for fields the form can
 * display; it does not implement a second grammar.
 *
 * <p>Values the form cannot represent are rejected with their key and line,
 * rather than being silently discarded on save.
 *
 * <p>The model covers every loader field that the form can write, including
 * faults, workload, pool limits, overrides, retry settings, and simulated
 * durations. A one-node pool written with {@code count: 1} is rejected because
 * its generated name differs from the form's one-node representation.
 */
public final class Draft {
    private Draft() {}

    /**
     * A group of machines with shared pool settings.
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
     * A service file and its per-RPC failures.
     *
     * <p>The failure map is empty for the short file form and populated for the
     * long form.
     */
    public record Runs(String file, java.util.Map<String, List<RpcFailure>> failures) {}

    /**
     * Per-node settings that override pool defaults.
     *
     * <p>An empty string or null number represents an omitted field, which falls
     * back to the pool value when the simulation runs.
     */
    public record Override(String node, String instance, String zone,
                           Double memoryMb, Double diskMb, List<Failure> failures) {}

    /**
     * A node-level failure declaration.
     *
     * <p>{@code kind} is a value from {@link dissaly.sim.Simulation.Kind}. The
     * remaining fields are populated according to that kind.
     *
     * <p>Exactly one of {@code atRefMs} and {@code perRefMs} is above zero.
     *
     * <p>{@code other} is used only by {@code partition} and {@code heal}.
     */
    public record Failure(String kind, double atRefMs, double perRefMs, String other,
                          double forRefMs, double factor, double noticeRefMs,
                          double restartAfterRefMs) {}

    /**
     * An RPC-level failure declaration.
     *
     * <p>{@code status} is a gRPC code name and empty for the other two kinds;
     * {@code factor} means something only to {@code slow}.
     */
    public record RpcFailure(String kind, String status, double factor, int perCalls) {}

    /** @param multiplier factor applied to the wait after each attempt */
    public record Retry(String method, int attempts, double backoffRefMs,
                        double multiplier, boolean unsafe) {}

    /** Network latency, jitter, and loss settings. */
    public record Net(double sameZoneRefMs, double crossZoneRefMs, double jitterRefMs, double loss) {}

    /** The simulated duration for one RPC.
     * @param perUnitRefNs per-unit duration in nanoseconds
     */
    public record Duration(String runs, String rpc, double fixedRefMs, long perUnitRefNs) {}

    /**
     * The workload at full size.
     *
     * @param source empty when the simulation named none, which is a workload
     *               generated from the seed rather than read
     */
    public record Workload(String source, String unit, long count) {}

    /**
     * A simulation in the form's data model.
     */
    public record Of(String name, long seed, double scale, Net net, List<Pool> pools,
                     List<Retry> retries, List<Duration> simulatedDuration, Workload input) {}

    /**
     * @param name source filename
     * @param text source content
     * @throws IllegalArgumentException the loader's own refusal, or this one's —
     *         both {@code file:line: message}, indistinguishable to whoever reads it
     */
    public static Of of(String name, String text) {
        Field root = Yaml.parse(name, text);
        var sc = Loader.of(root);   // Validate once, then read the validated tree.

        // Top-level values are taken from the validated loader result.

        var pools = new ArrayList<Pool>();
        for (var entry : root.at("nodes").map().entrySet()) {
            String poolName = entry.getKey();
            Field spec = entry.getValue();
            var overrides = new ArrayList<Override>();
            if (spec.opt("overrides").present()) {
                for (var o : spec.at("overrides").map().entrySet()) {
                    Field body = o.getValue();
                    // Check every entry so an unrepresentable override cannot be
                    // lost when the form writes the simulation.
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
                    "'" + poolName + "' has no zone: — the form always writes one, so a simulation"
                    + " without one did not come from it. Add a zone: and open it here again.");
            String instance = spec.at("instance").str();
            var zones = spec.at("zone").strings();
            // Preserve the short and long service representations separately.
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
            // A one-node pool with an explicit count gets a different generated
            // node name from the form's one-node representation.
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
        // An absent duration block is valid and produces no entries.
        Field priced = root.opt("simulatedDuration");
        for (var runs : (priced.present() ? priced.map() : java.util.Map.<String, Field>of()).entrySet()) {
            for (var rpc : runs.getValue().map().entrySet()) {
                Field body = rpc.getValue();
                durations.add(new Duration(runs.getKey(), rpc.getKey(),
                        body.opt("fixed").refMs(0),
                        Math.round(body.opt("perUnit").refMs(0) * 1e6)));
            }
        }

        // The loader has already validated the source, shape, and signs.
        var input = new Workload(sc.input().source() == null ? "" : sc.input().source(),
                sc.input().unit(), sc.input().count());

        var net = new Net(sc.net().sameZoneRefMs(), sc.net().crossZoneRefMs(),
                sc.net().jitterRefMs(), sc.net().loss());

        return new Of(name.replaceAll("\\.ya?ml$", ""), sc.seed(), sc.scale(),
                net, List.copyOf(pools),
                List.copyOf(retries), List.copyOf(durations), input);
    }

    /**
     * Reads node failures into the form's model.
     *
     * <p>Validation is performed by the loader. This method maps validated fields
     * to records.
     */
    private static List<Failure> failures(Field node) {
        var out = new ArrayList<Failure>();
        for (Field f : node.list()) {
            String kind = null;
            for (var k : dissaly.sim.Simulation.Kind.values())
                if (f.opt(k.key()).present()) kind = k.key();
            String other = "";
            double factor = 2;
            if (kind.equals("partition") || kind.equals("heal")) other = f.at(kind).str().trim();
            else if (kind.equals("degrade")) factor = f.at(kind).num();
            // Match the loader default so a round trip preserves an omitted
            // freeze duration.
            out.add(new Failure(kind, f.opt("at").refMs(0), f.opt("per").refMs(0), other,
                    f.opt("for").refMs(kind.equals("freeze") ? 1000 : 0), factor,
                    f.opt("notice").refMs(0), f.opt("restartAfter").refMs(0)));
        }
        return out;
    }

    /** Reads RPC failures for one service, keyed by RPC name. */
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
