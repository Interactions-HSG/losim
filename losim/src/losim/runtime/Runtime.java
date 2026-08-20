package losim.runtime;

import losim.api.*;

import losim.kernel.Kernel;
import losim.kernel.Task;
import losim.net.Codec;
import losim.kernel.Kernel;
import losim.net.Network;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

/** The engine: VMs, the network, RPC, blocking primitives and fault injection. */
public final class Runtime {

    final Kernel kernel;
    final Network net;
    final Codec codec;
    final Map<String, Vm> vms = new LinkedHashMap<>();
    final Metrics metrics = new Metrics();

    private final Map<Long, PendingCall> pending = new LinkedHashMap<>();
    private final Map<Object, List<Task>> waiters = new LinkedHashMap<>();
    private long nextCallId = 1;
    private Object result;
    private long completedAt = -1;
    private Object input;

    public Runtime(Kernel kernel, Network net, Codec codec) {
        this.kernel = kernel; this.net = net; this.codec = codec;
    }

    public Kernel kernel() { return kernel; }
    public Network net() { return net; }
    public Codec codec() { return codec; }
    public Metrics metrics() { return metrics; }
    public Collection<Vm> vms() { return vms.values(); }
    public Vm vm(String name) { return vms.get(name); }
    public Object result() { return result; }
    /** When the job actually finished, as opposed to when the event queue drained. */
    public long completedAt() { return completedAt; }
    public void input(Object in) { this.input = in; }
    public Object input() { return input; }

    public void register(Vm vm) { vms.put(vm.name, vm); }

    // ---------- blocking primitives ----------

    private Task current() {
        Task t = kernel.running();
        if (t == null) throw new IllegalStateException("no task is running; blocking calls need a VM task");
        return t;
    }

    public void sleepMs(long ms) {
        Task t = current();
        Vm vm = vms.get(t.vm);
        if (ms <= 0) return;
        vm.busyMs += ms;
        kernel.schedule(ms, "wake:" + t.name, () -> resume(vm, t));
        t.yieldToKernel();
    }

    /** Park the current task on a key until something wakes it. */
    public void await(Object key) {
        Task t = current();
        waiters.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        t.yieldToKernel();
    }

    /** Wake everything parked on a key, via the event queue so the kernel stays in charge. */
    public void wakeAll(Object key) {
        List<Task> ts = waiters.remove(key);
        if (ts == null) return;
        for (Task t : ts) {
            Vm vm = vms.get(t.vm);
            kernel.schedule(0, "wake:" + t.name, () -> resume(vm, t));
        }
    }

    /** All resumes funnel through here so a frozen VM simply does not run. */
    void resume(Vm vm, Task t) {
        if (t.isDone()) return;
        if (vm != null && vm.state == Vm.State.DEAD) return;
        if (vm != null && vm.state == Vm.State.FROZEN) { vm.deferred.add(t); return; }
        kernel.resume(t);
    }

    void thaw(Vm vm) {
        List<Task> d = new ArrayList<>(vm.deferred);
        vm.deferred.clear();
        for (Task t : d) kernel.schedule(0, "thaw:" + t.name, () -> resume(vm, t));
    }

    // ---------- tasks ----------

    public Task spawn(Vm vm, String name, Runnable body) {
        Task t = kernel.newTask(vm.name, name, body);
        vm.tasks.add(t);
        kernel.schedule(0, "start:" + name, () -> resume(vm, t));
        return t;
    }

    public void awaitTasks(List<Task> ts) {
        while (true) {
            boolean allDone = true;
            for (Task t : ts) if (!t.isDone()) { allDone = false; break; }
            if (allDone) return;
            await(taskKey(ts));
        }
    }

    private final Map<List<Task>, Object> taskKeys = new IdentityHashMap<>();
    private Object taskKey(List<Task> ts) { return taskKeys.computeIfAbsent(ts, k -> new Object()); }

    /** Called when any task finishes; wakes anything awaiting a group containing it. */
    public void taskCompleted(Task t) {
        for (Map.Entry<List<Task>, Object> e : new ArrayList<>(taskKeys.entrySet()))
            if (e.getKey().contains(t)) wakeAll(e.getValue());
    }

    // ---------- messaging (Style A) ----------

    public void send(Vm from, String toVm, Object payload) {
        Vm to = vms.get(toVm);
        if (to == null) throw new Faults.Unreachable("no such VM: " + toVm);
        byte[] encoded = codec.encode(payload);
        long wireLen = encoded.length + Payloads.logicalBytes(payload);
        Network.Locality loc = net.locality(from.name, to.name, from.zone, to.zone);
        metrics.messages++;
        metrics.bytes += wireLen;
        from.bytesOut += wireLen;
        if (loc == Network.Locality.CROSS_ZONE) { metrics.crossZoneBytes += wireLen; from.crossZoneBytes += wireLen; }

        kernel.log("send", from.name, "to", to.name, "type", payload.getClass().getSimpleName(),
                "bytes", wireLen, "locality", loc.name(), "value", Values.render(payload));

        if (net.blocked(from.name, to.name) || net.dropped(loc, kernel.rng())) {
            metrics.rpcDropped++;
            kernel.log("drop", from.name, "to", to.name, "type", payload.getClass().getSimpleName());
            return;
        }
        long latency = net.latencyMs(loc, wireLen, from.spec.netGbps(), kernel.rng());
        kernel.schedule(latency, "deliver->" + to.name, () -> deliver(from.name, to, payload, wireLen));
    }

    private void deliver(String fromName, Vm to, Object payload, long bytes) {
        if (to.isDead()) return;
        to.bytesIn += bytes;
        kernel.log("recv", to.name, "from", fromName, "type", payload.getClass().getSimpleName(),
                "bytes", bytes, "value", Values.render(payload));
        Dispatch.messageHandler(to, payload).ifPresent(h ->
                spawn(to, "on" + payload.getClass().getSimpleName() + "@" + kernel.now(), () -> {
                    Ctx ctx = to.contexts.get(h.program());
                    try {
                        long cost = to.effectiveCostMs(h.costMs());
                        if (cost > 0) sleepMs(cost);
                        h.method().invoke(h.program(), ctx, new VmRef(fromName), payload);
                    } catch (Throwable e) {
                        rethrow(to, e);
                    }
                }));
    }

    // ---------- RPC (Style B) ----------

    private static final class PendingCall {
        final Task task; final Vm callerVm;
        boolean settled; Object value; RuntimeException error;
        PendingCall(Task t, Vm vm) { this.task = t; this.callerVm = vm; }
    }

    @SuppressWarnings("unchecked")
    public <P extends Peer> List<P> peers(Vm from, Class<P> peerType) {
        Class<?> service = Dispatch.serviceOf(peerType);
        List<P> out = new ArrayList<>();
        for (Vm vm : vms.values()) {
            if (!vm.hosts(service)) continue;
            out.add((P) Proxy.newProxyInstance(peerType.getClassLoader(), new Class<?>[]{peerType},
                    new PeerHandler(from, vm.name, peerType, service)));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public <P extends Peer> Optional<P> localPeer(Vm from, String onVm, Class<P> peerType) {
        Class<?> service = Dispatch.serviceOf(peerType);
        Vm target = vms.get(onVm);
        if (target == null || !target.hosts(service)) return Optional.empty();
        return Optional.of((P) Proxy.newProxyInstance(peerType.getClassLoader(), new Class<?>[]{peerType},
                new PeerHandler(from, target.name, peerType, service)));
    }

    private final ThreadLocal<Long> deadline = ThreadLocal.withInitial(() -> 0L);

    public <T> T within(long ms, java.util.function.Supplier<T> body) {
        long prev = deadline.get();
        deadline.set(ms);
        try { return body.get(); } finally { deadline.set(prev); }
    }

    private final class PeerHandler implements InvocationHandler {
        final Vm from; final String toVm; final Class<?> peerType; final Class<?> service;
        PeerHandler(Vm from, String toVm, Class<?> peerType, Class<?> service) {
            this.from = from; this.toVm = toVm; this.peerType = peerType; this.service = service;
        }
        @Override public Object invoke(Object proxy, Method m, Object[] args) {
            switch (m.getName()) {
                case "name": if (m.getParameterCount() == 0) return toVm; break;
                case "toString": if (m.getParameterCount() == 0) return peerType.getSimpleName() + "@" + toVm;
                    break;
                case "equals": if (m.getParameterCount() == 1)
                        return args[0] instanceof Peer p && p.name().equals(toVm); break;
                case "hashCode": if (m.getParameterCount() == 0) return toVm.hashCode(); break;
                default: break;
            }
            return rpc(from, toVm, service, m, args == null ? new Object[0] : args, deadline.get());
        }
    }

    Object rpc(Vm from, String toVm, Class<?> service, Method clientMethod, Object[] args, long deadlineMs) {
        Vm to = vms.get(toVm);
        Method serverMethod = Dispatch.serverMethod(service, clientMethod);
        Object arg = args.length > 0 ? args[0] : null;
        byte[] encoded = arg == null ? new byte[0] : codec.encode(arg);
        long wireLen = encoded.length + Payloads.logicalBytes(arg);
        Network.Locality loc = net.locality(from.name, toVm, from.zone, to.zone);

        long id = nextCallId++;
        metrics.rpcCalls++;
        metrics.messages++;
        metrics.bytes += wireLen;
        from.bytesOut += wireLen;
        if (loc == Network.Locality.CROSS_ZONE) { metrics.crossZoneBytes += wireLen; from.crossZoneBytes += wireLen; }

        kernel.log("rpc_call", from.name, "to", toVm, "method", service.getSimpleName() + "." + clientMethod.getName(),
                "bytes", wireLen, "locality", loc.name(), "call", id, "arg", Values.render(arg));

        Task caller = current();
        PendingCall pc = new PendingCall(caller, from);
        pending.put(id, pc);

        boolean lost = net.blocked(from.name, toVm) || net.dropped(loc, kernel.rng());
        if (!lost) {
            long latency = net.latencyMs(loc, wireLen, from.spec.netGbps(), kernel.rng());
            kernel.schedule(latency, "req->" + toVm, () -> serve(id, from.name, to, service, serverMethod, arg, loc));
        } else {
            metrics.rpcDropped++;
            kernel.log("drop", from.name, "to", toVm, "call", id);
        }

        long dl = deadlineMs > 0 ? deadlineMs : 30_000;
        kernel.schedule(dl, "deadline:" + id, () -> {
            PendingCall p = pending.get(id);
            if (p != null && !p.settled) {
                metrics.rpcTimeouts++;
                kernel.log("rpc_timeout", from.name, "to", toVm,
                        "method", service.getSimpleName() + "." + clientMethod.getName(), "call", id);
                complete(id, null, new Faults.Timeout(toVm + " did not answer "
                        + service.getSimpleName() + "." + clientMethod.getName() + " within " + dl + "ms"));
            }
        });

        caller.yieldToKernel();
        pending.remove(id);
        if (pc.error != null) throw pc.error;
        return pc.value;
    }

    private void serve(long id, String fromName, Vm to, Class<?> service, Method serverMethod,
                       Object arg, Network.Locality loc) {
        if (to.isDead()) return;                      // dead machines simply never answer
        Program impl = to.provider(service);
        if (impl == null) return;
        long inBytes = arg == null ? 0 : codec.encode(arg).length + Payloads.logicalBytes(arg);
        to.bytesIn += inBytes;
        Ctx ctx = to.contexts.get(impl);
        spawn(to, "serve:" + serverMethod.getName() + "#" + id, () -> {
            try {
                to.mem.add(inBytes);                              // the request is held while served
                long declared = Dispatch.costOf(serverMethod);
                long cost = to.effectiveCostMs(declared);
                kernel.log("handler_start", to.name, "method", service.getSimpleName() + "." + serverMethod.getName(),
                        "call", id, "costMs", cost, "arg", Values.render(arg));
                if (cost > 0) sleepMs(cost);
                Object out = arg == null ? serverMethod.invoke(impl, ctx)
                                         : serverMethod.invoke(impl, ctx, arg);
                to.mem.release(inBytes);
                long outLen = out == null ? 0 : codec.encode(out).length + Payloads.logicalBytes(out);
                to.bytesOut += outLen;
                metrics.messages++;
                metrics.bytes += outLen;
                if (loc == Network.Locality.CROSS_ZONE) { metrics.crossZoneBytes += outLen; to.crossZoneBytes += outLen; }
                kernel.log("handler_end", to.name, "method", service.getSimpleName() + "." + serverMethod.getName(),
                        "call", id, "bytes", outLen, "result", Values.render(out));
                long back = net.latencyMs(loc, outLen, to.spec.netGbps(), kernel.rng());
                kernel.schedule(back, "reply->" + fromName, () -> complete(id, out, null));
            } catch (Throwable e) {
                to.mem.release(inBytes);
                Throwable cause = unwrap(e);
                kernel.log("handler_error", to.name, "call", id, "error", cause.getClass().getSimpleName(),
                        "message", String.valueOf(cause.getMessage()));
                if (cause instanceof Faults.OutOfMemory) { kill(to, "oom"); return; }
                RuntimeException re = cause instanceof RuntimeException r ? r
                        : new RuntimeException(cause.getMessage(), cause);
                kernel.schedule(1, "replyerr->" + fromName, () -> complete(id, null, re));
            }
        });
    }

    private void complete(long id, Object value, RuntimeException err) {
        PendingCall pc = pending.get(id);
        if (pc == null || pc.settled) return;
        pc.settled = true; pc.value = value; pc.error = err;
        resume(pc.callerVm, pc.task);
    }

    // ---------- faults ----------

    public void kill(Vm vm, String reason) {
        if (!vm.alive()) return;
        vm.state = Vm.State.DEAD;
        vm.diedAt = kernel.now();
        vm.disk.crash();                                    // unflushed writes never happened
        metrics.kills++;
        kernel.log("kill", vm.name, "reason", reason);
        for (Task t : vm.tasks) kernel.cancel(t);
        for (PendingCall pc : pending.values()) { /* callers time out; that is the lesson */ }
    }

    public void freeze(Vm vm, long ms) {
        if (!vm.alive()) return;
        vm.state = Vm.State.FROZEN;
        kernel.log("freeze", vm.name, "ms", ms);
        kernel.schedule(ms, "thaw:" + vm.name, () -> {
            if (vm.state == Vm.State.FROZEN) {
                vm.state = Vm.State.ALIVE;
                kernel.log("thaw", vm.name);
                thaw(vm);
            }
        });
    }

    public void degrade(Vm vm, double cpu) {
        vm.cpuMultiplier = 1.0 / Math.max(0.0001, cpu);
        kernel.log("degrade", vm.name, "cpu", cpu);
    }

    public void exhaustCredits(Vm vm) {
        vm.creditMsRemaining = 0;
        kernel.log("credits_exhausted", vm.name);
    }

    public void spotReclaim(Vm vm, long noticeMs) {
        if (!vm.alive()) return;
        kernel.log("spot_notice", vm.name, "noticeMs", noticeMs);
        Dispatch.terminateHandlers(vm).forEach(h ->
                spawn(vm, "terminate", () -> {
                    try { h.method().invoke(h.program(), vm.contexts.get(h.program())); }
                    catch (Throwable e) { rethrow(vm, e); }
                }));
        kernel.schedule(noticeMs, "reclaim:" + vm.name, () -> kill(vm, "spot_reclaim"));
    }

    public void restart(Vm vm) {
        vm.state = Vm.State.ALIVE;
        vm.mem.reset();                                     // memory does not survive
        vm.tasks.clear();
        vm.bootedAt = kernel.now();
        kernel.log("restart", vm.name);
    }

    // ---------- result ----------

    public void done(Object value) {
        if (result == null) {
            result = value;
            completedAt = kernel.now();
            kernel.log("done", current().vm, "value", Values.render(value));
        }
    }

    static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while (t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null) t = ite.getCause();
        return t;
    }

    private void rethrow(Vm vm, Throwable e) {
        Throwable cause = unwrap(e);
        if (cause instanceof Faults.OutOfMemory) { kill(vm, "oom"); return; }
        if (cause instanceof Task.Killed k) throw k;
        if (cause instanceof RuntimeException re) throw re;
        throw new RuntimeException(cause);
    }
}
