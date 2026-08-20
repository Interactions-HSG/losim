package losim.cli;

import losim.api.Invariant;
import losim.runtime.Sim;
import losim.scenario.Node;
import losim.scenario.Scenario;
import losim.scenario.ScenarioLoader;
import losim.verify.Verifier;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** The command line. Errors are messages, not stack traces. */
public final class Main {

    public static void main(String[] args) {
        if (args.length == 0) { usage(); System.exit(2); }
        String cmd = args[0];
        Map<String, String> opt = options(args);
        try {
            switch (cmd) {
                case "run" -> run(args[1], opt);
                case "check" -> check(args[1]);
                case "verify" -> verify(opt);
                case "sweep" -> sweep(args[1], opt);
                case "fork" -> fork(args[1], opt);
                case "gen" -> gen(args[1], opt);
                case "price" -> price(args[1], opt);
                default -> { usage(); System.exit(2); }
            }
        } catch (Node.ConfigError e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (Invariant.Violation e) {
            System.err.println("invariant violated: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println(cmd + ": " + e.getMessage());
            System.exit(1);
        }
    }

    static void usage() {
        System.out.println("""
            losim <command>

              run <scenario.yaml>   --cp <classes> [--seed N] [--out trace.json] [--package P] [--quiet]
              check <scenario.yaml>                          validate the scenario, with line numbers
              verify --cp <classes>                          reject nondeterministic student code
              sweep <scenario.yaml> --cp <classes>           run the sweep: block
              gen <file.proto>      --out <dir> [--package P]
              price <scenario.yaml> --cp <classes>           the five-bucket bill for a run
              fork <scenario.yaml>  --cp <classes> --at <ms> --faults kill:w0,freeze:w1
            """);
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(key, args[++i]);
            else m.put(key, "true");
        }
        return m;
    }

    static ClassLoader loaderFor(Map<String, String> opt) throws Exception {
        String cp = opt.getOrDefault("cp", "");
        List<URL> urls = new ArrayList<>();
        for (String part : cp.split(":")) if (!part.isBlank()) urls.add(Path.of(part).toUri().toURL());
        return new URLClassLoader(urls.toArray(new URL[0]), Main.class.getClassLoader());
    }

    static void price(String file, Map<String, String> opt) throws Exception {
        Scenario s = ScenarioLoader.load(Path.of(file));
        long seed = opt.containsKey("seed") ? Long.parseLong(opt.get("seed")) : s.seed;
        Sim.Result r = runOnce(s, opt, seed, Path.of(file).toAbsolutePath().getParent());
        System.out.println(s.name + " — bill for one run (seed " + seed + ")\n");
        System.out.print(r.pnl().render());
        System.out.println("""
            \n  Money is the aggregator, never the replacement: every line above carries
            the technical quantity it came from, so the measure stays visible.""".stripIndent());
    }

    static void gen(String proto, Map<String, String> opt) throws Exception {
        Path out = Path.of(opt.getOrDefault("out", "gen"));
        losim.gen.ProtoGen.generate(Path.of(proto), out, opt.get("package"));
        System.out.println("generated Java for " + proto + " -> " + out);
    }

    static void check(String file) throws Exception {
        Scenario s = ScenarioLoader.load(Path.of(file));
        int vms = 0;
        for (Scenario.VmGroup g : s.groups) vms += ScenarioLoader.expandNames(g).size();
        System.out.println("ok: " + s.name + " — " + vms + " VMs, " + s.faults.size() + " faults, "
                + s.invariants.size() + " invariants, seed " + s.seed + ", run_until " + s.runUntilMs + "ms");
    }

    static void verify(Map<String, String> opt) throws Exception {
        String cp = opt.get("cp");
        if (cp == null) throw new IllegalArgumentException("verify needs --cp <classes>");
        List<String> problems = Verifier.verifyTree(Path.of(cp));
        if (problems.isEmpty()) { System.out.println("ok: no nondeterminism found in " + cp); return; }
        for (String p : problems) System.err.println(p);
        System.err.println(problems.size() + " problem(s); a run would not be reproducible");
        System.exit(1);
    }

    static Sim.Result runOnce(Scenario s, Map<String, String> opt, long seed, Path baseDir) throws Exception {
        ClassLoader cl = loaderFor(opt);
        Sim sim = new Sim(s, cl, opt.get("package")).baseDir(baseDir);
        return sim.run(seed);
    }

    static void run(String file, Map<String, String> opt) throws Exception {
        Scenario s = ScenarioLoader.load(Path.of(file));
        long seed = opt.containsKey("seed") ? Long.parseLong(opt.get("seed")) : s.seed;
        Sim.Result r = runOnce(s, opt, seed, Path.of(file).toAbsolutePath().getParent());

        String out = opt.getOrDefault("out", "trace.json");
        r.trace().writeTo(Path.of(out));

        List<String> failures = checkInvariants(s, r, loaderFor(opt), opt.get("package"));
        if (!opt.containsKey("quiet")) summarise(s, r, seed, out, failures);
        if (!failures.isEmpty()) System.exit(1);
    }

    static List<String> checkInvariants(Scenario s, Sim.Result r, ClassLoader cl, String pkg) {
        List<String> failures = new ArrayList<>();
        for (Scenario.InvariantSpec spec : s.invariants) {
            try {
                if (spec.check.equals("done_within")) {
                    long ms = Long.parseLong(spec.args.getOrDefault("ms", "0"));
                    if (r.output() == null || r.endedAtMs() > ms)
                        throw new Invariant.Violation("not done within " + ms + "ms");
                    continue;
                }
                Class<?> c = load(cl, pkg, spec.check);
                Invariant inv = (Invariant) c.getDeclaredConstructor().newInstance();
                inv.check(r);
            } catch (Invariant.Violation v) {
                failures.add("  ✗ " + spec.name + ": " + v.getMessage());
            } catch (Exception e) {
                failures.add("  ✗ " + spec.name + ": could not run check '" + spec.check + "' — " + e);
            }
        }
        return failures;
    }

    static Class<?> load(ClassLoader cl, String pkg, String name) throws ClassNotFoundException {
        if (name.contains(".")) return Class.forName(name, true, cl);
        if (pkg != null && !pkg.isEmpty()) {
            try { return Class.forName(pkg + "." + name, true, cl); } catch (ClassNotFoundException ignored) { }
        }
        return Class.forName(name, true, cl);
    }

    static void summarise(Scenario s, Sim.Result r, long seed, String out, List<String> failures) {
        Map<String, Object> m = r.metrics();
        System.out.println(s.name + "  seed=" + seed + "  ended=" + r.endedAtMs() + "ms");
        System.out.println("  output   " + r.output());
        System.out.println("  messages " + m.get("messages") + "  bytes " + m.get("bytes")
                + "  cross-zone " + m.get("crossZoneBytes"));
        System.out.println("  rpc      " + m.get("rpcCalls") + " calls, " + m.get("rpcTimeouts")
                + " timeouts, " + m.get("rpcDropped") + " dropped");
        System.out.println("  trace    " + r.trace().size() + " events -> " + out);
        System.out.println("  cost     " + r.pnl().currency + " " + String.format("%.4f", r.pnl().cost())
                + "   (capacity " + r.pnl().byBucket().get("capacity")
                + ", consumption " + r.pnl().byBucket().get("consumption")
                + ", incidents " + r.pnl().byBucket().get("incidents") + ")");
        if (failures.isEmpty() && !s.invariants.isEmpty()) System.out.println("  ✓ all invariants hold");
        for (String f : failures) System.out.println(f);
    }

    /** Apply one matrix cell, e.g. workers.instance = c5.xlarge. */
    static void applyOverride(Scenario s, String path, String value) {
        int dot = path.indexOf('.');
        if (dot < 0) throw new IllegalArgumentException("matrix key must be <group>.<field>, got '" + path + "'");
        String groupKey = path.substring(0, dot), field = path.substring(dot + 1);
        Scenario.VmGroup g = s.groups.stream().filter(x -> x.key.equals(groupKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("matrix names unknown group '" + groupKey + "'"));
        switch (field) {
            case "instance" -> g.instance = value;
            case "market" -> g.market = value;
            case "count" -> g.count = Integer.parseInt(value);
            case "programs" -> g.programs = new ArrayList<>(List.of(value.split("\\+")));
            case "availability_zone" -> g.zones = new ArrayList<>(List.of(value.split("\\+")));
            default -> throw new IllegalArgumentException("matrix cannot set '" + field + "'");
        }
    }

    record Cell(Map<String, String> assignment) {
        String label() {
            List<String> parts = new ArrayList<>();
            assignment.forEach((k, v) -> parts.add(k.substring(k.indexOf('.') + 1) + "=" + v));
            return String.join(" ", parts);
        }
    }

    static List<Cell> cells(Map<String, List<String>> matrix) {
        List<Cell> out = new ArrayList<>();
        out.add(new Cell(new LinkedHashMap<>()));
        for (Map.Entry<String, List<String>> e : matrix.entrySet()) {
            List<Cell> next = new ArrayList<>();
            for (Cell c : out)
                for (String v : e.getValue()) {
                    Map<String, String> m = new LinkedHashMap<>(c.assignment());
                    m.put(e.getKey(), v);
                    next.add(new Cell(m));
                }
            out = next;
        }
        return out;
    }

    static void sweep(String file, Map<String, String> opt) throws Exception {
        Path path = Path.of(file);
        Scenario probe = ScenarioLoader.load(path);
        if (probe.sweep == null) throw new IllegalArgumentException(file + " has no 'sweep:' block");
        List<Long> seeds = probe.sweep.seeds.isEmpty() ? List.of(probe.seed) : probe.sweep.seeds;
        List<Cell> grid = cells(probe.sweep.matrix);
        ClassLoader cl = loaderFor(opt);
        Path base = path.toAbsolutePath().getParent();

        System.out.println(probe.name + " — " + grid.size() + " configuration(s) x "
                + seeds.size() + " seed(s) = " + (grid.size() * seeds.size()) + " runs\n");
        System.out.printf("  %-44s %8s %10s %10s %s%n",
                "configuration", "ok/runs", "median ms", "cost", "violations");

        String[] currency = {"CHF"};
        record Outcome(String label, int ok, int runs, long medianMs, double cost, int violations) {}
        List<Outcome> outcomes = new ArrayList<>();

        for (Cell cell : grid) {
            int ok = 0, violations = 0;
            double costSum = 0;
            List<Long> times = new ArrayList<>();
            for (long seed : seeds) {
                Scenario s = ScenarioLoader.load(path);
                for (Map.Entry<String, String> e : cell.assignment().entrySet())
                    applyOverride(s, e.getKey(), e.getValue());
                Sim.Result r = new Sim(s, cl, opt.get("package")).baseDir(base).run(seed);
                currency[0] = r.pnl().currency;
                List<String> f = checkInvariants(s, r, cl, opt.get("package"));
                if (f.isEmpty()) ok++; else violations++;
                times.add(r.endedAtMs());
                costSum += r.pnl().cost();
            }
            Collections.sort(times);
            long median = times.get(times.size() / 2);
            outcomes.add(new Outcome(cell.label().isEmpty() ? "(as written)" : cell.label(),
                    ok, seeds.size(), median, costSum / seeds.size(), violations));
        }

        outcomes.sort((a, b) -> {
            int c = Integer.compare(b.violations() == 0 ? 1 : 0, a.violations() == 0 ? 1 : 0);
            return c != 0 ? c : Double.compare(a.cost(), b.cost());
        });
        for (Outcome o : outcomes)
            System.out.printf("  %-44s %4d/%-3d %10d %10.4f %s%n", o.label(), o.ok(), o.runs(),
                    o.medianMs(), o.cost(), o.violations() == 0 ? "-" : o.violations() + " ✗");

        System.out.println("\n  cheapest configuration that still works: "
                + outcomes.stream().filter(o -> o.violations() == 0).findFirst()
                        .map(o -> o.label() + " at " + String.format("%.4f", o.cost()) + " " + currency[0])
                        .orElse("none — every configuration violated an invariant"));
    }

    static void fork(String file, Map<String, String> opt) throws Exception {
        Scenario base = ScenarioLoader.load(Path.of(file));
        long at = Long.parseLong(opt.getOrDefault("at", "0"));
        String faults = opt.getOrDefault("faults", "");
        ClassLoader cl = loaderFor(opt);
        long seed = opt.containsKey("seed") ? Long.parseLong(opt.get("seed")) : base.seed;

        List<String> branches = new ArrayList<>();
        branches.add("");                                        // the control branch
        branches.addAll(Arrays.stream(faults.split(",")).filter(x -> !x.isBlank()).toList());

        System.out.println("forking " + base.name + " at " + at + "ms (seed " + seed + ")");
        for (String b : branches) {
            Scenario s = ScenarioLoader.load(Path.of(file));
            String label = b.isEmpty() ? "no fault" : b;
            if (!b.isEmpty()) {
                String[] kv = b.split(":");
                Scenario.FaultSpec f = new Scenario.FaultSpec();
                f.atMs = at; f.kind = kv[0]; f.target = kv.length > 1 ? kv[1] : null;
                f.durationMs = 3000; f.noticeMs = 2000; f.cpu = 0.1;
                s.faults.add(f);
            }
            Sim.Result r = new Sim(s, cl, opt.get("package")).baseDir(Path.of(file).toAbsolutePath().getParent()).run(seed);
            List<String> viol = checkInvariants(s, r, cl, opt.get("package"));
            System.out.printf("  %-16s ended %6dms  output=%s%s%n", label, r.endedAtMs(),
                    String.valueOf(r.output()), viol.isEmpty() ? "" : "  ✗ " + viol.size() + " violation(s)");
        }
    }
}
