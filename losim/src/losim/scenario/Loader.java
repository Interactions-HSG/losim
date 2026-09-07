package losim.scenario;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import losim.res.InstanceCatalog;
import losim.runtime.Cost;
import losim.runtime.Retry;
import losim.scenario.Scenario.*;

/**
 * A scenario file, checked before anything runs.
 *
 * <p>Everything that can be wrong here is caught at load with the line it was
 * written on: an unknown instance type, a fault aimed at a machine that does not
 * exist, a duration that forgot to say what kind of time it is, a key that is a
 * typo for a real one. None of those should be discovered halfway through a run as
 * a puzzling number.
 */
public final class Loader {
    private Loader() {}

    public static Scenario load(Path file) throws IOException {
        return of(Yaml.parse(file));
    }

    /**
     * A scenario, with a second file's weather laid over it.
     *
     * <p>For running somebody else's scenario in a world they did not write —
     * an examiner asking what a submission does when a machine dies, a sweep
     * asking what it does under a heavier afternoon. The alternative is editing
     * their YAML with a text tool, which is how a harness comes to depend on
     * where they happened to put their whitespace.
     *
     * <p><b>The overlay may only change the weather.</b> Faults, chaos, retries,
     * the network and the seed are replaceable; the fleet, the job and the scale
     * are not. That is the line that keeps the result meaningful: a
     * scenario whose machines had been swapped out underneath it is no longer a
     * run of their design, and an examiner would be asking them about somebody
     * else's system.
     */
    public static Scenario overlay(Scenario base, Path file) throws IOException {
        Node over = Yaml.parse(file);
        over.onlyAllows("seed", "network", "faults", "chaos", "retries", "tightMargin");
        var names = new LinkedHashSet<String>();
        for (MachineSpec m : base.machines()) names.add(m.name());

        return new Scenario(
                base.file(),
                over.opt("seed").present() ? (long) over.at("seed").num(base.seed()) : base.seed(),
                base.job(),
                base.scale(),
                base.units(),
                base.machines(),
                over.opt("network").present() ? network(over.opt("network")) : base.net(),
                over.opt("faults").present() ? faults(over.opt("faults"), names, over) : base.faults(),
                over.opt("chaos").present() ? chaos(over.opt("chaos"), base.machines()) : base.chaos(),
                over.opt("retries").present() ? retries(over.opt("retries")) : base.retries(),
                base.takes(),
                over.opt("tightMargin").present() ? over.at("tightMargin").bool(false) : base.tightMargin(),
                base.mode());
    }

    public static Scenario of(Node root) {
        root.onlyAllows("seed", "job", "scale", "machines",
                        "network", "faults", "chaos", "retries", "takes", "tightMargin", "mode");

        long seed = (long) root.opt("seed").num(1);
        String job = root.at("job").str();
        double scale = root.opt("scale").num(1);
        if (scale < 1) throw root.at("scale").fail(
                "scale is how many times bigger the design is than the run measuring it, so it"
                + " starts at 1. A scale below one would be a design smaller than the thing"
                + " already being run, which is the run itself.");

        var machines = machines(root.at("machines"));
        var names = new LinkedHashSet<String>();
        for (MachineSpec m : machines)
            if (!names.add(m.name())) throw root.at("machines").fail(
                    "two machines are both called '" + m.name() + "'");

        var net = network(root.opt("network"));
        var faults = faults(root.opt("faults"), names, root);
        var chaos = chaos(root.opt("chaos"), machines);
        var retries = retries(root.opt("retries"));
        var takes = takes(root.opt("takes"));

        var mode = mode(root.opt("mode"));
        if (mode == Scenario.Mode.SCALED && scale <= 1)
            throw root.at("mode").fail("scaled mode measures a small run and projects it up, and"
                    + " at scale 1 there is nothing above the run to project to. Raise the scale,"
                    + " or run it directly.");

        var s = new Scenario(root.where().split(":")[0], seed, job, scale,
                0, machines, net, faults, chaos, retries, takes,
                root.opt("tightMargin").bool(false), mode);
        return s.withUnits(s.fullUnits());
    }

    private static Scenario.Mode mode(Node node) {
        if (!node.present()) return Scenario.Mode.DIRECT;
        String m = node.str().trim().toUpperCase();
        try { return Scenario.Mode.valueOf(m); }
        catch (IllegalArgumentException e) {
            throw node.fail("mode is 'direct' or 'scaled', not '" + node.str() + "'. There are"
                    + " only two: scaled mode always uses the engine, because a hand-declared"
                    + " shrink factor would be a third mode whose numbers nobody could account for.");
        }
    }

    // ----------------------------------------------------------------- machines

    private static List<MachineSpec> machines(Node node) {
        var out = new ArrayList<MachineSpec>();
        for (var entry : node.map().entrySet()) {
            String poolName = entry.getKey();
            Node spec = entry.getValue();
            spec.onlyAllows("instance", "zone", "runs", "count", "prefix",
                            "memoryMb", "diskMb", "overrides");

            String instance = spec.at("instance").str();
            checkInstance(spec.at("instance"), instance);
            var zones = spec.opt("zone").present() ? spec.at("zone").strings() : List.of("default");
            var runs = spec.opt("runs").present() ? spec.at("runs").strings()
                                                  : List.<String>of();
            int count = spec.opt("count").integer(0);

            if (count == 0) {                              // a single, named machine
                out.add(new MachineSpec(poolName, poolName, instance, zones.get(0), runs,
                        capOf(spec, "memoryMb"), capOf(spec, "diskMb"), spec.where()));
                continue;
            }
            if (count < 0) throw spec.at("count").fail("a pool cannot have " + count + " machines");
            String prefix = spec.opt("prefix").str(poolName);
            for (int i = 0; i < count; i++) {
                String name = prefix + i;
                Node over = spec.opt("overrides").present()
                        ? spec.at("overrides").opt(name) : spec.opt("overrides");
                String inst = over.present() && over.opt("instance").present()
                        ? over.at("instance").str() : instance;
                if (over.present()) {
                    over.onlyAllows("instance", "zone", "memoryMb", "diskMb");
                    checkInstance(over.opt("instance").present() ? over.at("instance") : spec, inst);
                }
                String zone = over.present() && over.opt("zone").present()
                        ? over.at("zone").str() : zones.get(i % zones.size());
                out.add(new MachineSpec(name, poolName, inst, zone, runs,
                        over.present() && over.opt("memoryMb").present()
                                ? capOf(over, "memoryMb") : capOf(spec, "memoryMb"),
                        over.present() && over.opt("diskMb").present()
                                ? capOf(over, "diskMb") : capOf(spec, "diskMb"),
                        over.present() ? over.where() : spec.where()));
            }
        }
        if (out.isEmpty()) throw node.fail("a scenario needs at least one machine");
        return out;
    }

    private static Double capOf(Node spec, String key) {
        return spec.opt(key).present() ? spec.at(key).num() : null;
    }

    private static void checkInstance(Node where, String name) {
        if (!InstanceCatalog.has(name))
            throw where.fail("unknown instance type '" + name + "'; known types: "
                    + String.join(", ", InstanceCatalog.all().keySet()));
    }

    // ------------------------------------------------------------------ network

    private static NetSpec network(Node node) {
        if (!node.present()) return NetSpec.none();
        node.onlyAllows("sameZone", "crossZone", "jitter", "loss");
        double loss = node.opt("loss").num(0);
        if (loss < 0 || loss > 1) throw node.at("loss").fail("loss is a probability, from 0 to 1");
        return new NetSpec(node.opt("sameZone").refMs(0), node.opt("crossZone").refMs(0),
                           node.opt("jitter").refMs(0), loss);
    }

    // ------------------------------------------------------------------- faults

    private static List<Fault> faults(Node node, java.util.Set<String> machines, Node root) {
        var out = new ArrayList<Fault>();
        for (Node f : node.list()) {
            f.onlyAllows("at", "kill", "freeze", "degrade", "spot_reclaim", "partition",
                         "heal", "restart", "for", "factor", "notice", "restart_after");
            double at = f.at("at").refMs();
            Kind kind = null;
            String target = null, other = null;
            for (Kind k : Kind.values()) {
                String key = k.name().toLowerCase();
                if (!f.opt(key).present()) continue;
                if (kind != null) throw f.fail("this fault does two things at once ("
                        + kind.name().toLowerCase() + " and " + key + "); write them as two");
                kind = k;
                if (k == Kind.PARTITION || k == Kind.HEAL) {
                    var pair = f.at(key).strings();
                    if (pair.size() != 2)
                        throw f.at(key).fail(key + " takes exactly two machines, got " + pair.size());
                    target = pair.get(0);
                    other = pair.get(1);
                } else {
                    target = f.at(key).str();
                }
            }
            if (kind == null) throw f.fail("a fault has to do something: "
                    + "kill, freeze, degrade, spot_reclaim, partition, heal or restart");
            check(f, machines, target);
            if (other != null) check(f, machines, other);

            double forMs = f.opt("for").refMs(kind == Kind.FREEZE ? 1000 : 0);
            if (kind == Kind.DEGRADE && !f.opt("factor").present())
                throw f.fail("degrade needs a factor: how many times slower the machine becomes");
            out.add(new Fault(at, kind, target, other, forMs,
                    f.opt("factor").num(1), f.opt("notice").refMs(0),
                    f.opt("restart_after").refMs(0), f.where()));
        }
        return out;
    }

    private static void check(Node f, java.util.Set<String> machines, String name) {
        if (!machines.contains(name))
            throw f.fail("there is no machine called '" + name + "' in this scenario; "
                    + "it has " + String.join(", ", machines));
    }

    // -------------------------------------------------------------------- chaos

    private static List<Chaos> chaos(Node node, List<MachineSpec> machines) {
        var out = new ArrayList<Chaos>();
        var pools = machines.stream().map(MachineSpec::pool).distinct().toList();
        for (Node c : node.list()) {
            for (var entry : c.map().entrySet()) {
                Kind kind;
                String verb = entry.getKey();
                try { kind = Kind.valueOf(verb.toUpperCase()); }
                catch (IllegalArgumentException e) {
                    throw entry.getValue().fail("chaos cannot '" + verb + "'; it can "
                            + "kill, freeze or degrade");
                }
                if (kind != Kind.KILL && kind != Kind.FREEZE && kind != Kind.DEGRADE)
                    throw entry.getValue().fail(verb + " happens at a moment, not at a rate; "
                            + "put it under faults:");
                Node body = entry.getValue();
                body.onlyAllows("every", "among", "factor", "for");
                String among = body.at("among").str();
                if (!pools.contains(among) && machines.stream().noneMatch(m -> m.name().equals(among)))
                    throw body.at("among").fail("'" + among + "' is neither a pool nor a machine; "
                            + "this scenario has pools " + String.join(", ", pools));
                out.add(new Chaos(kind, body.at("every").refMs(), among,
                        body.opt("factor").num(2), body.opt("for").refMs(1000), body.where()));
            }
        }
        return out;
    }

    // -------------------------------------------------------------------- takes

    /**
     * What each rpc costs, under the class that serves it.
     *
     * <pre>
     * takes:
     *   Mapper:
     *     Map:  { refMs: 20 }
     *     Note: { refMs: 1 }
     * </pre>
     *
     * <p><b>Keyed by what {@code runs:} names, not by the rpc.</b> A duration is a
     * property of the code that runs, not of the operation: two implementations of
     * one rpc placed in one fleet is how a design is compared with another, and a
     * table keyed on the rpc would say they cost the same. That is also the shape
     * the annotation this replaces had, so no scenario's numbers move.
     *
     * <p>Flattened here to {@code Class.Rpc}, which nothing outside this file
     * writes; the file itself stays two levels, because a class with four rpcs
     * repeating its own name four times is a file nobody proof-reads.
     *
     * <p>Nothing is checked here beyond the shape. Whether a name is a class this
     * fleet places, and whether that class serves that rpc, are questions about
     * classes the loader has not loaded — it never loads one — so they are asked by
     * {@link losim.runtime.Fleet} once the machines are up, and refused there with
     * this line.
     */
    private static Map<String, Cost> takes(Node node) {
        var out = new LinkedHashMap<String, Cost>();
        if (!node.present()) return out;
        for (var runs : node.map().entrySet()) {
            for (var rpc : runs.getValue().map().entrySet()) {
                Node body = rpc.getValue();
                body.onlyAllows("refMs", "refNsPerUnit");
                // Plain numbers: the key already says which unit each one is in.
                // A duration written as `2 refMs` elsewhere has to say so because
                // nothing around it does.
                double refMs = body.opt("refMs").num(0);
                double perRecord = body.opt("refNsPerUnit").num(0);
                if (refMs < 0 || perRecord < 0) throw body.fail("a call cannot take negative time");
                out.put(runs.getKey().trim() + "." + rpc.getKey().trim(),
                        new Cost(refMs, perRecord, body.where()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ retries

    private static List<Retry> retries(Node node) {
        var out = new ArrayList<Retry>();
        for (Node r : node.list()) {
            r.onlyAllows("method", "attempts", "backoff", "multiplier", "unsafe");
            int attempts = r.at("attempts").integer();
            if (attempts < 1) throw r.at("attempts").fail("a call is attempted at least once");
            out.add(new Retry(r.at("method").str(), attempts,
                    r.opt("backoff").refMs(0), r.opt("multiplier").num(1),
                    r.opt("unsafe").bool(false), r.where()));
        }
        return out;
    }
}
