package losim.runtime;

import io.grpc.*;
import losim.res.Meter;
import losim.trace.Telemetry;
import losim.trace.Values;

/**
 * Applies network delay, byte accounting, and tracing around an outgoing call.
 *
 * <h2>Latency accounting</h2>
 * The round trip is paid once, in {@code onClose}, on the thread that
 * delivers the response. For a blocking call that is the caller's own thread,
 * which is waiting anyway; for an async call it is the channel's executor, so the
 * caller is never blocked by it. Neither the callee's pool thread nor the
 * handler's measured duration is touched.
 *
 * <p>Outbound delay is folded into the return path. This avoids occupying a
 * handler thread or blocking an asynchronous caller; spacing is reconstructed by
 * the scaling engine.
 */
final class ClientSide implements ClientInterceptor {

    /**
     * Converts a reference-time deadline to wall-clock time for gRPC.
     *
     * <p>A student writes {@code withDeadlineAfter(200, MILLISECONDS)} meaning 200
     * reference milliseconds, like every other duration in losim (D3). gRPC's own
     * deadline machinery works in wall clock, so the remaining time is divided by
     * {@code k_time} here — the one place the caller's intent is still visible.
     * Left alone, an 800 refMs deadline would fire eighty times too early against a
     * cost that was itself compressed.
     */
    private static CallOptions inRealTime(CallOptions opts, double kTime) {
        Deadline d = opts.getDeadline();
        if (d == null || kTime == 1.0) return opts;
        long refNs = Math.max(0, d.timeRemaining(java.util.concurrent.TimeUnit.NANOSECONDS));
        return opts.withDeadline(Deadline.after((long) (refNs / kTime),
                java.util.concurrent.TimeUnit.NANOSECONDS));
    }

    private final Machine from;
    private final String to;

    ClientSide(Machine from, String to) { this.from = from; this.to = to; }

    @Override public <Q, S> ClientCall<Q, S> interceptCall(
            MethodDescriptor<Q, S> md, CallOptions opts, Channel ch) {

        long a0 = Meter.allocNow(), t0 = System.nanoTime();
        final Telemetry tel = from.tel();
        final String method = Wire.dotted(md.getFullMethodName());
        final Machine target = from.machines().machine(to);
        final Net net = from.machines().net;
        final CallOptions call = inRealTime(opts, from.machines().clock.kTime());
        final Deadline deadline = call.getDeadline();

        // Capture deadline and fixed service cost for timeout diagnostics.
        final Double deadlineRefMs = opts.getDeadline() == null ? null
                : opts.getDeadline().timeRemaining(java.util.concurrent.TimeUnit.NANOSECONDS) / 1e6;
        final Cost callee = target == null ? null : target.takenBy(md.getFullMethodName());
        // The fixed part only. `perUnit` is not knowable here — the handler
        // declares its count while it runs — so what is reported is a lower bound
        // on the cost, and a deadline under even that cannot be met.
        final Double declaredRefMs = callee == null || callee.fixedRefMs() <= 0 ? null
                : callee.fixedRefMs() * target.effectiveFactor();
        from.charge(Meter.allocNow() - a0, System.nanoTime() - t0);

        // All dropped requests are observed by the caller as a deadline wait.
        if (target != null && !target.alive)
            return new Dropped<>(from, to, method, call, "unreachable");
        if (!net.reaches(from.name, to))
            return new Dropped<>(from, to, method, call, "partitioned");
        if (net.drops())
            return new Dropped<>(from, to, method, call, "lost");
        // A request-level drop is decided before the callee is invoked.
        if (target != null)
            for (Machine.Drawn f : target.failuresOn(md.getFullMethodName()))
                if (f.spec().kind() == losim.sim.Simulation.RpcKind.DROP && f.fires())
                    return new Dropped<>(from, to, method, call, "dropped by " + to);

        final double rttRefMs = net.roundTripRefMs(from.zone, target == null ? from.zone : target.zone);
        // Cross-zone traffic is counted while both call endpoints are known.
        final boolean crossZone = target != null && !from.zone.equals(target.zone);
        // Preserve the destination region for region-specific billing.
        final String toRegion = target == null ? null : target.region;

        return new ForwardingClientCall.SimpleForwardingClientCall<>(ch.newCall(md, call)) {
            private Telemetry.Span span;

            @Override public void start(Listener<S> responseListener, Metadata headers) {
                long b0 = Meter.allocNow(), n0 = System.nanoTime();
                span = tel.open(from.name, "rpc", method, "to", to);
                // The callee opens its span under this id, so causality survives
                // the boundary (D8 rule 2).
                headers.put(Machines.PARENT, Long.toString(span.id));
                tel.event(from.name, "rpc_call", "to", to, "method", method, "call", span.id);

                var wrapped = new ForwardingClientCallListener
                        .SimpleForwardingClientCallListener<S>(responseListener) {

                    @Override public void onMessage(S message) {
                        long c0 = Meter.allocNow(), m0 = System.nanoTime();
                        long in = Wire.sizeOf(message);
                        from.bytesIn.addAndGet(in);
                        // The answer crosses the same zone boundary the question
                        // did, and is charged for it. Counting only the request
                        // would make a fetch look free: a shuffle asks for a
                        // region in forty bytes and is sent a megabyte back, so
                        // the direction that is not counted is the direction all
                        // the data is travelling in.
                        if (crossZone) from.egress(toRegion, in);
                        if (tel.payloads()) span.detail.put("result", Values.render(message));
                        from.chargeTo(span, Meter.allocNow() - c0, System.nanoTime() - m0);
                        super.onMessage(message);
                    }

                    @Override public void onClose(Status status, Metadata trailers) {
                        // Outside any bracket: network time is the simulated
                        // world's, not losim's overhead.
                        from.machines().clock.spend(rttRefMs);

                        // Paying the network after the fact would otherwise let a
                        // call succeed that the wire had already outlasted. The
                        // deadline is checked once the delay has been served, so
                        // latency and withDeadlineAfter mean the same thing.
                        Status effective = status;
                        if (status.isOk() && deadline != null && deadline.isExpired())
                            effective = Status.DEADLINE_EXCEEDED.withDescription(
                                    "the network outlasted the deadline");

                        long c0 = Meter.allocNow(), m0 = System.nanoTime();
                        if (rttRefMs > 0) span.detail.put("netRefMs", Machine.round(rttRefMs));
                        // Closed first, and only then asked how long it took. Passing
                        // grossMs() as an argument evaluates it at this call site, which
                        // is before close() sets the span's end — so every call ever
                        // recorded said it had lasted −1 milliseconds.
                        tel.close(span, effective.getCode().name());
                        span.detail.put("ms", Machine.round(span.grossMs()));
                        if (!effective.isOk()) {
                            boolean timedOut = effective.getCode() == Status.Code.DEADLINE_EXCEEDED;
                            // The two numbers only on the timeout, and only when
                            // there are two: an error that is not a deadline has
                            // nothing to do with one, and saying "deadline null"
                            // beside every failure would bury the case that matters.
                            boolean impossible = timedOut && deadlineRefMs != null
                                    && declaredRefMs != null && declaredRefMs >= deadlineRefMs;
                            tel.event(from.name, timedOut ? "rpc_timeout" : "rpc_error",
                                      "to", to, "method", method, "call", span.id,
                                      "status", effective.getCode().name(),
                                      "deadlineRefMs", timedOut ? Machine.round(deadlineRefMs) : null,
                                      "declaredRefMs", timedOut ? Machine.round(declaredRefMs) : null,
                                      "unmeetable", impossible ? true : null);
                        }
                        from.chargeTo(span, Meter.allocNow() - c0, System.nanoTime() - m0);
                        super.onClose(effective, trailers);
                    }
                };
                from.chargeTo(span, Meter.allocNow() - b0, System.nanoTime() - n0);
                super.start(wrapped, headers);
            }

            @Override public void sendMessage(Q message) {
                long b0 = Meter.allocNow(), n0 = System.nanoTime();
                long bytes = Wire.sizeOf(message);
                from.bytesOut.addAndGet(bytes);
                if (crossZone) from.egress(toRegion, bytes);
                if (span != null) {
                    span.detail.put("bytes", bytes);
                    if (tel.payloads()) span.detail.put("arg", Values.render(message));
                }
                from.chargeTo(span, Meter.allocNow() - b0, System.nanoTime() - n0);
                super.sendMessage(message);
            }
        };
    }
}
