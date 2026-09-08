package losim.sim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import losim.res.InstanceCatalog;
import losim.runtime.Cost;
import losim.runtime.Retry;
import losim.sim.Simulation.*;

/**
 * A simulation file, checked before anything runs.
 *
 * <p>Everything that can be wrong here is caught at load with the line it was
 * written on: an unknown instance type, a fault aimed at a machine that does not
 * exist, a duration that forgot to say what kind of time it is, a key that is a
 * typo for a real one. None of those should be discovered halfway through a run as
 * a puzzling number.
 */
public final class Loader {
    private Loader() {}

    public static Simulation load(Path file) throws IOException {
        return of(Yaml.parse(file));
    }

    /**
     * A simulation, with a second file's weather laid over it.
     *
     * <p>For running somebody else's simulation in a world they did not write —
     * an examiner asking what a submission does when a machine dies, a sweep
     * asking what it does under a heavier afternoon. The alternative is editing
     * their YAML with a text tool, which is how a harness comes to depend on
     * where they happened to put their whitespace.
     *
     * <p><b>The overlay may only change the weather.</b> Failures, retries, the
     * network and the seed are replaceable; the cluster, the job and the scale are
     * not. That is the line that keeps the result meaningful: a simulation whose
     * nodes had been swapped out underneath it is no longer a run of their design,
     * and an examiner would be asking them about somebody else's system.
     *
     * <p>Failures live inside the nodes they happen to, so an overlay names nodes
     * to reach them — and may say nothing else about one. A node named here has
     * its whole {@code failures:} list replaced, not added to: an overlay that
     * layered a second kill on top of theirs would be asking what their design
     * does about a bad afternoon nobody can read off either file.
     */
    public static Simulation overlay(Simulation base, Path file) throws IOException {
        Field over = Yaml.parse(file);
        over.onlyAllows("seed", "network", "nodes", "retries");

        var nodes = base.nodes();
        if (over.opt("nodes").present()) {
            var byName = new LinkedHashMap<String, Field>();
            for (var e : over.at("nodes").map().entrySet()) {
                e.getValue().onlyAllows("failures");
                byName.put(e.getKey(), e.getValue());
            }
            var known = new LinkedHashSet<String>();
            for (NodeSpec m : base.nodes()) known.add(m.name());
            for (var e : byName.entrySet())
                if (!known.contains(e.getKey())) throw e.getValue().fail(
                        "there is no node called '" + e.getKey() + "' in " + base.file()
                        + "; it has " + String.join(", ", known)
                        + ". An overlay lays weather over a system it did not write, so it can"
                        + " only name nodes that system already has.");
            var out = new ArrayList<NodeSpec>();
            for (NodeSpec m : base.nodes()) {
                Field o = byName.get(m.name());
                out.add(o == null ? m : new NodeSpec(m.name(), m.pool(), m.instance(), m.zone(),
                        m.runs(), failures(o.opt("failures")), m.memoryCapMb(), m.diskCapMb(),
                        m.where()));
            }
            checkPartitions(out);
            nodes = List.copyOf(out);
        }

        return new Simulation(
                base.file(),
                over.opt("seed").present() ? (long) over.at("seed").num(base.seed()) : base.seed(),
                base.scale(),
                base.units(),
                base.input(),
                nodes,
                over.opt("network").present() ? network(over.opt("network")) : base.net(),
                over.opt("retries").present() ? retries(over.opt("retries")) : base.retries(),
                base.simulatedDuration(),
                base.mode());
    }

    public static Simulation of(Field root) {
        root.onlyAllows("seed", "scale", "nodes", "input", "network", "retries",
                        "simulatedDuration");

        long seed = (long) root.opt("seed").num(1);
        double scale = root.opt("scale").num(1);
        if (scale < 1) throw root.at("scale").fail(
                "scale is how many times bigger the design is than the run measuring it, so it"
                + " starts at 1. A scale below one would be a design smaller than the thing"
                + " already being run, which is the run itself.");

        var machines = machines(root.at("nodes"));
        var names = new LinkedHashSet<String>();
        for (NodeSpec m : machines)
            if (!names.add(m.name())) throw root.at("nodes").fail(
                    "two nodes are both called '" + m.name() + "'");

        checkPartitions(machines);

        var net = network(root.opt("network"));
        var retries = retries(root.opt("retries"));
        var takes = simulatedDuration(root.opt("simulatedDuration"));
        var input = input(root.opt("input"));

        // Derived, not declared. A simulation above scale 1 is a model of
        // something bigger and there is nothing else it could be; at scale 1 there
        // is nothing above the run to project to. A `mode:` key was a second way
        // to say what `scale:` already says, and the two could disagree.
        var mode = scale > 1 ? Simulation.Mode.SCALED : Simulation.Mode.DIRECT;

        var s = new Simulation(root.where().split(":")[0], seed, scale, 0,
                input, machines, net, retries, takes, mode);
        return s.withUnits(s.fullUnits());
    }

    // ----------------------------------------------------------------- machines

    /**
     * What a node runs: the service, and the file that implements it.
     *
     * <p>A path rather than a class name, and checked here rather than at run
     * start. A fully qualified class name is a string that looks right until a
     * classloader disagrees with it; a path is a thing that either exists or does
     * not, and saying so on the line it was written on is the difference between a
     * typo and a build that ran.
     *
     * <p>What is <b>not</b> checked here is whether the class implements the
     * service it is filed under. The loader loads nothing, so that question is
     * asked once the node has a bound server to answer it — the same discipline
     * {@code simulatedDuration:} is under.
     */
    private static Map<String, Simulation.ServiceSpec> runs(Field node) {
        var out = new LinkedHashMap<String, Simulation.ServiceSpec>();
        if (!node.present()) return out;
        if (!node.isMap()) throw node.fail(
                "runs: names a service and the .java file that implements it —"
                + " { Thumbnailer: src/Shrinker.java }. A list says only half of that,"
                + " and the half it leaves out is the one peers find a node by.");
        for (var e : node.map().entrySet()) {
            String service = e.getKey().trim();
            Field entry = e.getValue();
            // Shorthand when nothing goes wrong here, which is almost every entry;
            // longhand when something does, and then the file: is the same string
            // the shorthand would have been.
            boolean longhand = entry.isMap();
            if (longhand) entry.onlyAllows("file", "failures");
            Field value = longhand ? entry.at("file") : entry;
            String said = value.str().trim();
            if (!said.endsWith(".java")) throw value.fail(
                    "'" + said + "' is not a .java file. runs: names the file that implements a"
                    + " service, so that a name nothing can be found under is a line to fix rather"
                    + " than a run that starts and then cannot build anything.");
            Path file = Path.of(said);
            if (!Files.isRegularFile(file)) throw value.fail(
                    "there is no file at '" + said + "'. Paths are relative to the project — the"
                    + " directory holding proto/, src/ and the simulations.");
            String className;
            try {
                if (!JavaSource.declaresItsOwnName(file)) throw value.fail(
                        "'" + said + "' declares no type called " + JavaSource.simpleName(file)
                        + ", so nothing could be loaded under the name this path implies. A file"
                        + " and the class in it have to agree before anything else can.");
                className = JavaSource.className(file);
            } catch (java.io.IOException io) {
                throw value.fail("'" + said + "' could not be read: " + io.getMessage());
            }
            if (out.containsKey(service)) throw value.fail(
                    "this node runs '" + service + "' twice");
            var failures = longhand ? rpcFailures(entry.opt("failures"))
                                    : Map.<String, List<RpcFailure>>of();
            out.put(service, new Simulation.ServiceSpec(service, said, className,
                    failures, value.where()));
        }
        return out;
    }

    private static List<NodeSpec> machines(Field node) {
        var out = new ArrayList<NodeSpec>();
        for (var entry : node.map().entrySet()) {
            String poolName = entry.getKey();
            Field spec = entry.getValue();
            spec.onlyAllows("instance", "zone", "runs", "failures", "count", "prefix",
                            "memoryMb", "diskMb", "overrides");

            String instance = spec.at("instance").str();
            checkInstance(spec.at("instance"), instance);
            var zones = spec.opt("zone").present() ? spec.at("zone").strings() : List.of("default");
            var runs = runs(spec.opt("runs"));
            var failures = failures(spec.opt("failures"));
            int count = spec.opt("count").integer(0);

            if (count == 0) {                              // a single, named node
                out.add(new NodeSpec(poolName, poolName, instance, zones.get(0), runs,
                        failures, capOf(spec, "memoryMb"), capOf(spec, "diskMb"),
                        spec.where()));
                continue;
            }
            if (count < 0) throw spec.at("count").fail("a pool cannot have " + count + " nodes");
            String prefix = spec.opt("prefix").str(poolName);
            for (int i = 0; i < count; i++) {
                String name = prefix + i;
                Field over = spec.opt("overrides").present()
                        ? spec.at("overrides").opt(name) : spec.opt("overrides");
                String inst = over.present() && over.opt("instance").present()
                        ? over.at("instance").str() : instance;
                if (over.present()) {
                    over.onlyAllows("instance", "zone", "memoryMb", "diskMb", "failures");
                    checkInstance(over.opt("instance").present() ? over.at("instance") : spec, inst);
                }
                String zone = over.present() && over.opt("zone").present()
                        ? over.at("zone").str() : zones.get(i % zones.size());
                // The pool's failures, unless this node overrides them — replaced
                // rather than added to, the way its instance type is. Being the one
                // node that dies is the most interesting way to differ from your
                // pool, and overrides: is already where a pool says one of its
                // nodes is not like the others.
                var mine = over.present() && over.opt("failures").present()
                        ? failures(over.at("failures")) : failures;
                out.add(new NodeSpec(name, poolName, inst, zone, runs, mine,
                        over.present() && over.opt("memoryMb").present()
                                ? capOf(over, "memoryMb") : capOf(spec, "memoryMb"),
                        over.present() && over.opt("diskMb").present()
                                ? capOf(over, "diskMb") : capOf(spec, "diskMb"),
                        over.present() ? over.where() : spec.where()));
            }
        }
        if (out.isEmpty()) throw node.fail("a simulation needs at least one node");
        return out;
    }

    private static Double capOf(Field spec, String key) {
        return spec.opt(key).present() ? spec.at(key).num() : null;
    }

    private static void checkInstance(Field where, String name) {
        if (!InstanceCatalog.has(name))
            throw where.fail("unknown instance type '" + name + "'; known types: "
                    + String.join(", ", InstanceCatalog.all().keySet()));
    }

    // ------------------------------------------------------------------ network

    private static NetSpec network(Field node) {
        if (!node.present()) return NetSpec.none();
        node.onlyAllows("sameZone", "crossZone", "jitter", "loss");
        double loss = node.opt("loss").num(0);
        if (loss < 0 || loss > 1) throw node.at("loss").fail("loss is a probability, from 0 to 1");
        return new NetSpec(node.opt("sameZone").refMs(0), node.opt("crossZone").refMs(0),
                           node.opt("jitter").refMs(0), loss);
    }

    // ----------------------------------------------------------------- failures

    private static final List<Kind> REPEATABLE = List.of(Kind.KILL, Kind.FREEZE, Kind.DEGRADE);

    /**
     * For each kind, the keys that do nothing to it.
     *
     * <p>Refused rather than dropped. A {@code for:} on a degrade is the one worth
     * naming: nothing schedules an end to a one-time degrade, so the node stays
     * slow for the rest of the simulation and the file says otherwise. A key that
     * is read by nobody is a claim the run does not honour, and it reads back as a
     * design decision to whoever opens the file next.
     */
    private static final Map<Kind, List<String>> IGNORES = Map.of(
            Kind.KILL,         List.of("notice", "for"),
            Kind.FREEZE,       List.of("notice", "restartAfter"),
            Kind.DEGRADE,      List.of("notice", "for", "restartAfter"),
            Kind.RESTART,      List.of("notice", "for", "restartAfter"),
            Kind.SPOT_RECLAIM, List.of("for"),
            Kind.PARTITION,    List.of("notice", "for", "restartAfter"),
            Kind.HEAL,         List.of("notice", "for", "restartAfter"));

    /**
     * What happens to the node this block is written in.
     *
     * <pre>
     * failures:
     *   - { kill: true, at: 400 refMs, restartAfter: 300 refMs }
     *   - { degrade: 3, per: 2 refSeconds }
     *   - { partition: master, at: 900 refMs }
     * </pre>
     *
     * <p><b>No target.</b> The node is the block it is in, which is why this reader
     * takes no set of names to check one against: a failure can no longer be aimed
     * at a node that is not there, because there is no name to get wrong. A
     * partition is the exception and says so — reachability is a property of a
     * pair, so one of the two ends has to be written down.
     *
     * <p><b>A pool's failures belong to every node in it, separately.</b> A
     * {@code per: 2 refSeconds} on a pool of six is six nodes each failing every
     * two seconds on their own draw, not one of the six failing every two
     * seconds. That follows from where it is written — the block is the node —
     * and it is the reading that survives resizing the pool, which is the one
     * thing the probe grid does to it. For one node of a pool and not its
     * siblings, write it under {@code overrides:}.
     *
     * <p><b>{@code at:} or {@code per:}, and never both.</b> One is an instant, the
     * other a mean gap drawn exponentially. An entry with both would be two
     * failures written as one, and an entry with neither is a failure that never
     * happens — which reads in a trace exactly like a system that survived it.
     */
    private static List<Failure> failures(Field node) {
        var out = new ArrayList<Failure>();
        for (Field f : node.list()) {
            for (RpcKind r : RpcKind.values()) {
                String key = r.name().toLowerCase();
                if (f.opt(key).present()) throw f.at(key).fail(
                        key + " happens to an rpc, not to a node. Write it under the service in"
                        + " runs:, keyed by the rpc it happens to — that is what lets one bad"
                        + " replica be bad while its peers are fine.");
            }
            var allowed = new ArrayList<>(List.of("at", "per", "for", "notice", "restartAfter"));
            for (Kind k : Kind.values()) allowed.add(k.key());
            f.onlyAllows(allowed.toArray(new String[0]));

            Kind kind = null;
            String other = null;
            double factor = 1;
            for (Kind k : Kind.values()) {
                String key = k.key();
                if (!f.opt(key).present()) continue;
                if (kind != null) throw f.fail("this failure does two things at once ("
                        + kind.key() + " and " + key + "); write them as two");
                kind = k;
                if (k == Kind.PARTITION || k == Kind.HEAL) other = f.at(key).str().trim();
                else if (k == Kind.DEGRADE) factor = f.at(key).num();
                else if (!f.at(key).bool(false)) throw f.at(key).fail(
                        key + ": is written true, or left out. A node is not half killed.");
            }
            if (kind == null) throw f.fail("a failure has to do something: "
                    + "kill, freeze, degrade, spotReclaim, partition, heal or restart");
            for (String dead : IGNORES.get(kind))
                if (f.opt(dead).present()) throw f.at(dead).fail(
                        dead + ": does nothing to a " + kind.key() + ", so it would be a line"
                        + " this file says and the simulation does not do.");
            if (kind == Kind.DEGRADE && factor <= 1) throw f.at("degrade").fail(
                    "degrade is how many times slower the node becomes, so it is above 1."
                    + " A factor of " + factor + " is a node that is not degraded.");

            boolean at = f.opt("at").present(), per = f.opt("per").present();
            if (at && per) throw f.fail("this failure says both when it happens (at:) and how"
                    + " often (per:). Those are two failures, and one of them would be lost"
                    + " here. Write them as two.");
            if (!at && !per) throw f.fail("this failure never happens: it says neither when"
                    + " (at:) nor how often (per:). A failure that never fires reads in a"
                    + " trace exactly like a system that survived it.");
            if (per && !REPEATABLE.contains(kind)) throw f.at("per").fail(
                    kind.key() + " happens once, so it has an at: rather than a per:. It "
                    + (kind == Kind.PARTITION || kind == Kind.HEAL
                        ? "changes what reaches what, and stays changed until the other one undoes it."
                        : "either brings the node back or takes it away for good, and neither is"
                          + " a thing that can go on happening at a rate.") );

            double forMs = f.opt("for").refMs(kind == Kind.FREEZE ? 1000 : 0);
            out.add(new Failure(kind, f.opt("at").refMs(0), f.opt("per").refMs(0), other,
                    forMs, factor, f.opt("notice").refMs(0),
                    f.opt("restartAfter").refMs(0), f.where()));
        }
        return out;
    }

    /**
     * What happens to one rpc on one node.
     *
     * <pre>
     * runs:
     *   Thumbnailer:
     *     file: src/Shrinker.java
     *     failures:
     *       Thumbnail:
     *         - { status: UNAVAILABLE, per: 20 calls }
     * </pre>
     *
     * <p>Rates only. A failure that starts at an instant and stays is a property of
     * the node, and {@code degrade} already says it.
     *
     * <p>Whether the service actually serves an rpc of that name is not asked here.
     * The loader loads nothing, so it cannot know what a class serves — the same
     * reason {@code simulatedDuration:} defers its own key check. The line is
     * carried instead, and {@link losim.runtime.Machines} asks the bound server
     * once there is one.
     */
    private static Map<String, List<RpcFailure>> rpcFailures(Field node) {
        var out = new LinkedHashMap<String, List<RpcFailure>>();
        if (!node.present()) return out;
        if (!node.isMap()) throw node.fail(
                "failures: under a service is keyed by the rpc it happens to, because a service"
                + " with four rpcs is four different things to go wrong with.");
        for (var e : node.map().entrySet()) {
            String rpc = e.getKey().trim();
            var here = new ArrayList<RpcFailure>();
            for (Field f : e.getValue().list()) {
                for (Kind k : Kind.values()) {
                    String key = k.key();
                    if (f.opt(key).present()) throw f.at(key).fail(
                            key + " happens to a node, not to an rpc. Write it in the node's own"
                            + " failures: — the block it is in is the node it happens to.");
                }
                if (f.opt("at").present()) throw f.at("at").fail(
                        "an rpc fails at a rate rather than at an instant. A failure that begins"
                        + " at a moment and stays is a property of the node, and degrade says it.");
                f.onlyAllows("status", "slow", "drop", "per");
                RpcKind kind = null;
                String status = null;
                double factor = 1;
                for (RpcKind r : RpcKind.values()) {
                    String key = r.name().toLowerCase();
                    if (!f.opt(key).present()) continue;
                    if (kind != null) throw f.fail("this failure does two things at once ("
                            + kind.name().toLowerCase() + " and " + key + "); write them as two");
                    kind = r;
                    switch (r) {
                        case STATUS -> {
                            status = f.at("status").str().trim();
                            if (status.equals("OK")) throw f.at("status").fail(
                                    "OK is what a call that worked returns, so a failure cannot"
                                    + " be one. Pick the code the caller should have to handle.");
                            if (!Codes.known(status)) throw f.at("status").fail(
                                    "'" + status + "' is not a gRPC status code. The codes are "
                                    + Codes.listed() + ".");
                        }
                        case SLOW -> {
                            factor = f.at("slow").num();
                            if (factor <= 1) throw f.at("slow").fail(
                                    "slow is how many times its declared duration this call takes,"
                                    + " so it is above 1. A factor of " + factor + " is a call"
                                    + " that is not slow.");
                        }
                        case DROP -> {
                            if (!f.at("drop").bool(false)) throw f.at("drop").fail(
                                    "drop: is written true, or left out.");
                        }
                    }
                }
                if (kind == null) throw f.fail("an rpc failure is one of status:, slow: or drop:");
                here.add(new RpcFailure(kind, status, factor, f.at("per").calls(), f.where()));
            }
            if (here.isEmpty()) throw e.getValue().fail(
                    "'" + rpc + "' is listed under failures: with nothing wrong with it");
            out.put(rpc, List.copyOf(here));
        }
        return out;
    }

    /**
     * The far end of every partition and heal, checked once the whole cluster is
     * known.
     *
     * <p>Here rather than in {@link #failures} because a pool's failures are read
     * before the pool has been expanded into nodes, and half a cluster cannot
     * answer whether a name is in it.
     */
    private static void checkPartitions(List<NodeSpec> nodes) {
        var names = new LinkedHashSet<String>();
        for (NodeSpec m : nodes) names.add(m.name());
        for (NodeSpec m : nodes)
            for (Failure f : m.failures()) {
                if (f.other() == null) continue;
                if (f.other().equals(m.name())) throw new IllegalArgumentException(f.where()
                        + ": '" + m.name() + "' is partitioned from itself. A partition is"
                        + " between two nodes, and this block is already one of them.");
                if (!names.contains(f.other())) throw new IllegalArgumentException(f.where()
                        + ": there is no node called '" + f.other() + "'; this simulation has "
                        + String.join(", ", names));
            }
    }

    // -------------------------------------------------------------------- input

    /**
     * The workload, at full size.
     *
     * <pre>
     * input:
     *   source: data/frames/     # a file, a folder, or left out entirely
     *   unit:   frame
     *   count:  30000
     * </pre>
     *
     * <p>One number, because there is one number the engine varies. What used to
     * be here was a block of parts named by the job's own {@code shape()} — as
     * many numbers as the class declared, checked against it once the class was
     * loaded. The Job's own {@code .proto} says what its data is made of now, and
     * a simulation has nothing to add to that but how much of it there is.
     *
     * <p>{@code source:} is stat'ed here. A path is the one thing about a
     * workload that can be checked before anything runs, and a simulation that
     * spends its setup reading a file that is not there should say so on the line
     * naming it.
     */
    private static InputSpec input(Field node) {
        if (!node.present()) return InputSpec.none(node.where());
        node.onlyAllows("source", "unit", "count");

        String source = null;
        if (node.opt("source").present()) {
            source = node.at("source").str().trim();
            if (!Files.exists(Path.of(source))) throw node.at("source").fail(
                    "there is nothing at '" + source + "'. A source is a file or a folder,"
                    + " relative to the project — the directory holding proto/, src/ and the"
                    + " simulations. Leave it out entirely and Load generates the workload from"
                    + " the seed instead.");
        }

        String unit = node.opt("unit").str("unit").trim();
        if (unit.isEmpty()) throw node.at("unit").fail(
                "unit: is what one item is called, singular — frame, line, order. It is the same"
                + " word Losim.current().units(n) counts and perUnit: prices, so a blank one"
                + " leaves three numbers counting something nobody named.");

        double n = node.opt("count").num(1);
        if (n != Math.rint(n)) throw node.at("count").fail("count is " + n + ". A workload is"
                + " counted in whole things, and a count that is not whole is one whose unit is"
                + " wrong — say what one item is, and put that in unit:.");
        if (n < 1) throw node.at("count").fail("count is " + (long) n + ", which is not a"
                + " smaller run — it is no run.");
        return new InputSpec(source, unit, (long) n, node.where());
    }

    // -------------------------------------------------------------------- takes

    /**
     * What each rpc costs, under the class that serves it.
     *
     * <pre>
     * simulatedDuration:
     *   src/Shrinker.java:
     *     Thumbnail: { fixed: 3 refMs, perUnit: 240000 refNs }
     *     Pull:      { fixed: 1 refMs }
     * </pre>
     *
     * <p><b>Keyed by what {@code runs:} names, not by the rpc.</b> A duration is a
     * property of the code that runs, not of the operation: two implementations of
     * one rpc placed in one cluster is how a design is compared with another, and a
     * table keyed on the rpc would say they cost the same. That is also the shape
     * the annotation this replaces had, so no simulation's numbers move.
     *
     * <p>Flattened here to {@code Class.Rpc}, which nothing outside this file
     * writes; the file itself stays two levels, because a class with four rpcs
     * repeating its own name four times is a file nobody proof-reads.
     *
     * <p>Nothing is checked here beyond the shape. Whether a name is a class this
     * cluster places, and whether that class serves that rpc, are questions about
     * classes the loader has not loaded — it never loads one — so they are asked by
     * {@link losim.runtime.Machines} once the machines are up, and refused there with
     * this line.
     */
    private static Map<String, Cost> simulatedDuration(Field node) {
        var out = new LinkedHashMap<String, Cost>();
        if (!node.present()) return out;
        for (var runs : node.map().entrySet()) {
            for (var rpc : runs.getValue().map().entrySet()) {
                Field body = rpc.getValue();
                body.onlyAllows("fixed", "perUnit");
                // Durations, saying what kind of time they are, like every other
                // duration in the file. The unit used to be in the key — refMs,
                // refNsPerUnit — which made these the only two numbers in a
                // simulation whose kind of time was a spelling rather than a value.
                double refMs = body.opt("fixed").refMs(0);
                double perRecord = body.opt("perUnit").refMs(0);
                if (refMs < 0 || perRecord < 0) throw body.fail("a call cannot take negative time");
                out.put(runs.getKey().trim() + "." + rpc.getKey().trim(),
                        new Cost(refMs, perRecord, body.where()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ retries

    private static List<Retry> retries(Field node) {
        var out = new ArrayList<Retry>();
        for (Field r : node.list()) {
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
