package losim.scenario;

import losim.res.InstanceCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads and validates a scenario. Errors say file:line, never a stack trace. */
public final class ScenarioLoader {

    public static Scenario load(Path p) throws IOException {
        return parse(Files.readString(p), p.getFileName().toString());
    }

    public static Scenario parse(String text, String file) {
        Node root = Yaml.parse(text, file);
        Scenario s = new Scenario();

        if (root.opt("name") != null) s.name = root.get("name").str();
        if (root.opt("seed") != null) s.seed = root.get("seed").integer();
        if (root.opt("run_until") != null) s.runUntilMs = root.get("run_until").millis();
        if (root.opt("codec") != null) s.codec = requireOneOf(root.get("codec"), "record", "proto");
        if (root.opt("input") != null) s.input = root.get("input").str();
        if (root.opt("prices") != null) s.prices = root.get("prices").str();

        Node vms = root.opt("vms");
        if (vms == null) throw root.error("a scenario needs a 'vms:' section");
        for (Map.Entry<String, Node> e : vms.map().entrySet()) {
            s.groups.add(group(e.getKey(), e.getValue()));
        }
        if (s.groups.isEmpty()) throw vms.error("'vms:' declares no machines");

        Node net = root.opt("network");
        if (net != null) {
            if (net.opt("topology") != null) s.network.topology = requireOneOf(net.get("topology"), "mesh", "ring");
            if (net.opt("loss") != null) s.network.loss = fraction(net.get("loss"));
            if (net.opt("cross_zone_factor") != null) s.network.crossZoneFactor = net.get("cross_zone_factor").number();
            Node lat = net.opt("latency");
            if (lat != null) {
                if (lat.opt("mean") != null) s.network.meanMs = lat.get("mean").millis();
                if (lat.opt("stddev") != null) s.network.stddevMs = lat.get("stddev").millis();
            }
        }

        Node faults = root.opt("faults");
        if (faults != null) for (Node f : faults.list()) s.faults.add(fault(f));

        Node invs = root.opt("invariants");
        if (invs != null) for (Node i : invs.list()) {
            Scenario.InvariantSpec spec = new Scenario.InvariantSpec();
            spec.name = i.optOr("name", "invariant").str();
            spec.check = i.get("check").str();
            for (Map.Entry<String, Node> e : i.map().entrySet())
                if (!e.getKey().equals("name") && !e.getKey().equals("check"))
                    spec.args.put(e.getKey(), e.getValue().str());
            s.invariants.add(spec);
        }

        Node sw = root.opt("sweep");
        if (sw != null) {
            s.sweep = new Scenario.Sweep();
            Node seeds = sw.opt("seed");
            if (seeds != null) s.sweep.seeds = seedRange(seeds);
            Node matrix = sw.opt("matrix");
            if (matrix != null) for (Map.Entry<String, Node> e : matrix.map().entrySet()) {
                List<String> vals = new ArrayList<>();
                for (Node v : e.getValue().list()) vals.add(v.isList() ? joinList(v) : v.str());
                s.sweep.matrix.put(e.getKey(), vals);
            }
        }

        validate(s, root);
        return s;
    }

    private static String joinList(Node n) {
        List<String> parts = new ArrayList<>();
        for (Node x : n.list()) parts.add(x.str());
        return String.join("+", parts);
    }

    private static List<Long> seedRange(Node n) {
        List<Long> out = new ArrayList<>();
        for (Node v : n.list()) {
            String s = v.str();
            if (s.contains("..")) {
                String[] parts = s.split("\\.\\.");
                long from = Long.parseLong(parts[0].trim()), to = Long.parseLong(parts[1].trim());
                if (to - from > 100_000) throw v.error("seed range too large: " + s);
                for (long i = from; i <= to; i++) out.add(i);
            } else out.add(Long.parseLong(s.trim()));
        }
        return out;
    }

    private static Scenario.VmGroup group(String key, Node n) {
        Scenario.VmGroup g = new Scenario.VmGroup();
        g.key = key;
        Node programs = n.opt("programs");
        Node program = n.opt("program");
        if (programs != null) for (Node p : programs.list()) g.programs.add(p.str());
        else if (program != null) g.programs.add(program.str());
        else throw n.error("VM '" + key + "' declares no program (use 'program:' or 'programs:')");

        if (n.opt("prefix") != null) g.prefix = n.get("prefix").str();
        if (n.opt("instance") != null) g.instance = n.get("instance").str();
        if (n.opt("market") != null) g.market = requireOneOf(n.get("market"), "on-demand", "spot");
        if (n.opt("availability_zone") != null) {
            g.zones = new ArrayList<>();
            for (Node z : n.get("availability_zone").list()) g.zones.add(z.str());
        }
        if (n.opt("count") != null) {
            g.count = (int) n.get("count").integer();
            if (g.count < 1) throw n.get("count").error("count must be at least 1");
        } else {
            g.named = true;
        }
        Node ov = n.opt("overrides");
        if (ov != null) {
            if (g.named) throw ov.error("'overrides:' only applies to a group with 'count:'");
            for (Map.Entry<String, Node> e : ov.map().entrySet())
                g.overrides.put(e.getKey(), new LinkedHashMap<>(e.getValue().map()));
        }
        if (!InstanceCatalog.has(g.instance))
            throw (n.opt("instance") != null ? n.get("instance") : n)
                    .error("unknown instance type '" + g.instance + "'");
        return g;
    }

    private static Scenario.FaultSpec fault(Node n) {
        Scenario.FaultSpec f = new Scenario.FaultSpec();
        f.atMs = n.get("at").millis();
        for (String k : List.of("kill", "freeze", "degrade", "spot_reclaim", "exhaust_credits",
                                "partition", "heal", "restart", "kill_randomly")) {
            Node v = n.opt(k);
            if (v != null) {
                f.kind = k;
                if (k.equals("partition")) {
                    for (Node grp : v.list()) {
                        List<String> members = new ArrayList<>();
                        for (Node m : grp.list()) members.add(m.str());
                        f.groups.add(members);
                    }
                } else if (!k.equals("heal")) {
                    f.target = v.str();
                }
                break;
            }
        }
        if (f.kind == null) throw n.error("fault has no action (kill, freeze, degrade, spot_reclaim, …)");
        if (n.opt("cpu") != null) f.cpu = n.get("cpu").number();
        if (n.opt("for") != null) f.durationMs = n.get("for").millis();
        if (n.opt("notice") != null) f.noticeMs = n.get("notice").millis();
        if (n.opt("restart_after") != null) f.restartAfterMs = n.get("restart_after").millis();
        return f;
    }

    private static double fraction(Node n) {
        double v = n.number();
        if (v < 0 || v > 1) throw n.error("must be between 0 and 1, got " + v);
        return v;
    }

    private static String requireOneOf(Node n, String... allowed) {
        String v = n.str();
        for (String a : allowed) if (a.equals(v)) return v;
        throw n.error("expected one of " + String.join(", ", allowed) + ", got '" + v + "'");
    }

    /** Cross-checks that need the whole document. */
    private static void validate(Scenario s, Node root) {
        List<String> names = new ArrayList<>();
        for (Scenario.VmGroup g : s.groups) names.addAll(expandNames(g));

        Node vms = root.get("vms");
        for (Scenario.VmGroup g : s.groups) {
            List<String> members = expandNames(g);
            for (String k : g.overrides.keySet())
                if (!members.contains(k))
                    throw vms.get(g.key).get("overrides").error(
                            "override names '" + k + "', which is not a member of group '" + g.key
                                    + "' (members: " + String.join(", ", members) + ")");
        }
        for (Scenario.FaultSpec f : s.faults) {
            if (f.target != null && !names.contains(f.target) && !f.kind.equals("kill_randomly"))
                throw root.get("faults").error("fault targets unknown VM '" + f.target
                        + "' (known: " + String.join(", ", names) + ")");
            if (f.atMs > s.runUntilMs)
                throw root.get("faults").error("fault at " + f.atMs + "ms never fires; run_until is "
                        + s.runUntilMs + "ms");
        }
    }

    public static List<String> expandNames(Scenario.VmGroup g) {
        List<String> out = new ArrayList<>();
        if (g.named) { out.add(g.key); return out; }
        String prefix = g.prefix != null ? g.prefix : g.key.substring(0, 1);
        for (int i = 0; i < g.count; i++) out.add(prefix + i);
        return out;
    }
}
