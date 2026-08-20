package losim.runtime;

import losim.api.*;
import losim.kernel.Kernel;
import losim.net.Codec;
import losim.net.Network;
import losim.net.ProtoCodec;
import losim.net.RecordCodec;
import losim.res.InstanceCatalog;
import losim.res.InstanceSpec;
import losim.scenario.Node;
import losim.scenario.Scenario;
import losim.scenario.ScenarioLoader;
import losim.price.PnL;
import losim.price.PriceList;
import losim.price.Pricer;
import losim.trace.Trace;
import losim.trace.TraceEvent;

import java.util.*;

/** Builds a fleet from a scenario, runs it, and hands back the trace. */
public final class Sim {

    private final Scenario scenario;
    private final ClassLoader loader;
    private final String packagePrefix;

    private java.nio.file.Path baseDir;

    public Sim(Scenario scenario, ClassLoader loader, String packagePrefix) {
        this.scenario = scenario; this.loader = loader;
        this.packagePrefix = packagePrefix == null ? "" : packagePrefix;
    }

    public Sim baseDir(java.nio.file.Path dir) { this.baseDir = dir; return this; }

    public Result run(long seed) {
        Kernel kernel = new Kernel(seed);
        Network net = new Network();
        net.meanMs = scenario.network.meanMs;
        net.stddevMs = scenario.network.stddevMs;
        net.loss = scenario.network.loss;
        net.crossZoneFactor = scenario.network.crossZoneFactor;

        Codec codec = scenario.codec.equals("record") ? RecordCodec.INSTANCE : ProtoCodec.INSTANCE;
        Runtime rt = new Runtime(kernel, net, codec);
        kernel.onTaskFinished(rt::taskCompleted);
        rt.input(scenario.input);

        buildFleet(rt, kernel, seed);
        scheduleFaults(rt, kernel);

        kernel.runUntil(scenario.runUntilMs);
        boolean crashed = false;
        String failure = null;
        try {
            kernel.run();
        } catch (Kernel.SimulationFailed e) {
            crashed = true;
            failure = String.valueOf(e.getCause());
        }

        // The job's end is when it finished, not when the last stray timer expired.
        long endedAt = rt.completedAt() >= 0 ? rt.completedAt() : kernel.now();

        Trace trace = kernel.trace();
        trace.meta("name", scenario.name);
        trace.meta("seed", seed);
        trace.meta("codec", codec.name());
        trace.meta("endedAtMs", endedAt);
        trace.meta("traceEndMs", kernel.now());
        trace.meta("slices", kernel.slices());
        trace.meta("finished", !crashed);
        if (failure != null) trace.meta("failure", failure);
        trace.meta("metrics", rt.metrics().asMap());
        trace.meta("vms", vmMeta(rt));

        // Every lab gets a cost view; nothing in the lab has to ask for one.
        PriceList prices = priceList();
        PnL pnl = Pricer.price(traceMeta(trace), prices);
        trace.meta("pnl", pnl.asMap());

        return new Result(rt, trace, endedAt, !crashed, pnl);
    }

    private PriceList priceList() {
        if (scenario.prices == null || scenario.prices.isBlank()) return PriceList.defaults();
        try {
            java.nio.file.Path p = java.nio.file.Path.of(scenario.prices);
            if (!java.nio.file.Files.exists(p) && baseDir != null) p = baseDir.resolve(scenario.prices);
            return java.nio.file.Files.exists(p) ? PriceList.load(p) : PriceList.defaults();
        } catch (Exception e) { return PriceList.defaults(); }
    }

    private Map<String, Object> traceMeta(Trace t) {
        try {
            var f = Trace.class.getDeclaredField("meta");
            f.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) f.get(t);
            return m;
        } catch (ReflectiveOperationException e) { return Map.of(); }
    }

    private List<Object> vmMeta(Runtime rt) {
        List<Object> out = new ArrayList<>();
        for (Vm vm : rt.vms()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", vm.name);
            m.put("instance", vm.spec.name());
            m.put("zone", vm.zone);
            m.put("market", vm.market);
            m.put("programs", vm.programs.stream().map(p -> p.getClass().getSimpleName()).toList());
            m.put("busyMs", vm.busyMs);
            m.put("bytesIn", vm.bytesIn);
            m.put("bytesOut", vm.bytesOut);
            m.put("crossZoneBytes", vm.crossZoneBytes);
            m.put("memPeak", vm.mem.peak());
            m.put("state", vm.state.name());
            m.put("diedAt", vm.diedAt);
            out.add(m);
        }
        return out;
    }

    private void buildFleet(Runtime rt, Kernel kernel, long seed) {
        long ctxSeed = seed;
        for (Scenario.VmGroup g : scenario.groups) {
            List<String> names = ScenarioLoader.expandNames(g);
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                String instance = g.instance;
                String market = g.market;
                String zone = g.zones.get(i % g.zones.size());
                List<String> programs = new ArrayList<>(g.programs);

                Map<String, Node> ov = g.overrides.get(name);
                if (ov != null) {
                    if (ov.containsKey("instance")) instance = ov.get("instance").str();
                    if (ov.containsKey("market")) market = ov.get("market").str();
                    if (ov.containsKey("availability_zone")) zone = ov.get("availability_zone").str();
                    if (ov.containsKey("programs")) {
                        programs = new ArrayList<>();
                        for (Node p : ov.get("programs").list()) programs.add(p.str());
                    }
                }

                InstanceSpec spec = InstanceCatalog.get(instance);
                Vm vm = new Vm(name, spec, zone, market);
                rt.register(vm);
                for (String p : programs) {
                    Program prog = instantiate(p);
                    vm.programs.add(prog);
                    vm.contexts.put(prog, new Ctx(rt, vm, prog, ctxSeed++ * 1_000_003L));
                }
                kernel.log("boot", vm.name, "instance", spec.name(), "zone", zone, "market", market,
                        "programs", programs, "memoryMb", spec.memoryMb(), "vcpu", spec.vcpu());
            }
        }
        // start every program that takes initiative
        for (Vm vm : rt.vms()) {
            for (Program p : vm.programs) {
                if (!declaresMain(p)) continue;
                Ctx ctx = vm.contexts.get(p);
                rt.spawn(vm, "main:" + p.getClass().getSimpleName(), () -> {
                    try { p.main(ctx); }
                    catch (Faults.OutOfMemory e) { rt.kill(vm, "oom"); }
                    catch (losim.kernel.Task.Killed k) { throw k; }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
            }
            for (Dispatch.Handler h : Dispatch.timerHandlers(vm)) scheduleTimer(rt, kernel, vm, h);
        }
    }

    private void scheduleTimer(Runtime rt, Kernel kernel, Vm vm, Dispatch.Handler h) {
        OnTimer t = h.method().getAnnotation(OnTimer.class);
        long every = t.everyMs();
        if (every <= 0) return;
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!vm.alive()) return;
            rt.spawn(vm, "timer:" + h.method().getName(), () -> {
                try { h.method().invoke(h.program(), vm.contexts.get(h.program())); }
                catch (Throwable e) { throw new RuntimeException(Runtime.unwrap(e)); }
            });
            kernel.schedule(every, "timer", tick[0]);
        };
        kernel.schedule(every, "timer", tick[0]);
    }

    private static boolean declaresMain(Program p) {
        try {
            p.getClass().getMethod("main", Ctx.class);
            return !p.getClass().getMethod("main", Ctx.class).isDefault();
        } catch (NoSuchMethodException e) { return false; }
    }

    private void scheduleFaults(Runtime rt, Kernel kernel) {
        for (Scenario.FaultSpec f : scenario.faults) {
            kernel.scheduleAt(f.atMs, "fault:" + f.kind, () -> {
                Vm vm = f.target == null ? null : rt.vm(f.target);
                switch (f.kind) {
                    case "kill" -> {
                        rt.kill(vm, "killed");
                        if (f.restartAfterMs >= 0)
                            kernel.schedule(f.restartAfterMs, "restart", () -> rt.restart(vm));
                    }
                    case "freeze" -> rt.freeze(vm, f.durationMs > 0 ? f.durationMs : 1000);
                    case "degrade" -> rt.degrade(vm, f.cpu);
                    case "exhaust_credits" -> rt.exhaustCredits(vm);
                    case "spot_reclaim" -> rt.spotReclaim(vm, f.noticeMs > 0 ? f.noticeMs : 2000);
                    case "restart" -> rt.restart(vm);
                    case "heal" -> { rt.net().heal(); kernel.log("heal", "-"); }
                    case "partition" -> {
                        List<Set<String>> groups = new ArrayList<>();
                        for (List<String> g : f.groups) groups.add(new LinkedHashSet<>(g));
                        rt.net().partition(groups);
                        kernel.log("partition", "-", "groups", f.groups);
                    }
                    default -> kernel.log("unknown_fault", "-", "kind", f.kind);
                }
            });
        }
    }

    private Program instantiate(String className) {
        for (String candidate : candidates(className)) {
            try {
                Class<?> c = Class.forName(candidate, true, loader);
                Object o = c.getDeclaredConstructor().newInstance();
                if (!(o instanceof Program p))
                    throw new IllegalArgumentException(candidate + " does not implement losim.api.Program");
                return p;
            } catch (ClassNotFoundException ignored) {
                // try the next candidate
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot instantiate " + candidate + ": " + e, e);
            }
        }
        throw new IllegalArgumentException("program class not found: " + className
                + " (tried " + String.join(", ", candidates(className)) + ")");
    }

    private List<String> candidates(String name) {
        if (name.contains(".")) return List.of(name);
        List<String> out = new ArrayList<>();
        if (!packagePrefix.isEmpty()) out.add(packagePrefix + "." + name);
        out.add(name);
        return out;
    }

    /** The run's outcome, in the shape invariants and graders consume. */
    public static final class Result implements RunResult {
        private final Runtime rt;
        private final Trace trace;
        private final long endedAt;
        private final boolean finished;
        private final PnL pnl;

        Result(Runtime rt, Trace trace, long endedAt, boolean finished, PnL pnl) {
            this.rt = rt; this.trace = trace; this.endedAt = endedAt; this.finished = finished; this.pnl = pnl;
        }

        /** The bill for this run. Available to every lab, and to graders. */
        public PnL pnl() { return pnl; }

        public Trace trace() { return trace; }
        public Runtime runtime() { return rt; }

        @Override public Object output() { return rt.result(); }
        @Override public Object input() { return rt.input(); }
        @Override public long endedAtMs() { return endedAt; }
        @Override public boolean finished() { return finished; }
        @Override public Map<String, Object> metrics() { return rt.metrics().asMap(); }
        @Override public Map<String, Object> bill() { return pnl.asMap(); }

        @Override public List<Map<String, Object>> events() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (TraceEvent e : trace.events()) {
                Map<String, Object> m = new LinkedHashMap<>(e.detail());
                m.put("t", e.t());
                m.put("kind", e.kind());
                m.put("vm", e.vm());
                out.add(m);
            }
            return out;
        }

        @Override public List<Map<String, Object>> events(String kind) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> e : events()) if (kind.equals(e.get("kind"))) out.add(e);
            return out;
        }
    }
}
