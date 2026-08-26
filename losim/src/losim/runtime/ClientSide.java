package losim.runtime;

import io.grpc.*;
import losim.res.Meter;
import losim.trace.Telemetry;
import losim.trace.Values;

/**
 * What losim does around a call the machine makes: the network, the byte count,
 * and the causal link to whatever the callee then does.
 *
 * <h2>Where the latency is slept, and why there</h2>
 * The whole round trip is paid once, in {@code onClose}, on the thread that
 * delivers the response. For a blocking call that is the caller's own thread,
 * which is waiting anyway; for an async call it is the channel's executor, so the
 * caller is never blocked by it. Neither the callee's pool thread nor the
 * handler's measured duration is touched.
 *
 * <p>The approximation this buys is worth naming: the handler starts one leg
 * earlier than it should, because the outbound delay is folded into the return.
 * Spacing is precisely what the scaler engine reconstructs rather than measures
 * (D5), and the alternatives all cost something worse — an occupied core, an
 * extra thread per call, or a blocked async caller.
 */
final class ClientSide implements ClientInterceptor {

    /**
     * Rewrites a deadline from reference time into real time.
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
        final Machine target = from.fleet().machine(to);
        final Net net = from.fleet().net;
        final CallOptions call = inRealTime(opts, from.fleet().clock.kTime());
        final Deadline deadline = call.getDeadline();
        from.charge(Meter.allocNow() - a0, System.nanoTime() - t0);

        // Three ways a message never arrives, and the caller cannot tell them
        // apart — which is the point. It waits out its own deadline either way.
        if (target != null && !target.alive)
            return new Dropped<>(from, to, method, call, "unreachable");
        if (!net.reaches(from.name, to))
            return new Dropped<>(from, to, method, call, "partitioned");
        if (net.drops())
            return new Dropped<>(from, to, method, call, "lost");

        final double rttRefMs = net.roundTripRefMs(from.zone, target == null ? from.zone : target.zone);
        // Traffic between availability zones is billed and traffic within one is
        // not, so it has to be counted apart from the rest rather than derived
        // afterwards: only here are both ends of the call known at once.
        final boolean crossZone = target != null && !from.zone.equals(target.zone);

        return new ForwardingClientCall.SimpleForwardingClientCall<>(ch.newCall(md, call)) {
            private Telemetry.Span span;

            @Override public void start(Listener<S> responseListener, Metadata headers) {
                long b0 = Meter.allocNow(), n0 = System.nanoTime();
                span = tel.open(from.name, "rpc", method, "to", to);
                // The callee opens its span under this id, so causality survives
                // the boundary (D8 rule 2).
                headers.put(Fleet.PARENT, Long.toString(span.id));
                tel.event(from.name, "rpc_call", "to", to, "method", method, "call", span.id);

                var wrapped = new ForwardingClientCallListener
                        .SimpleForwardingClientCallListener<S>(responseListener) {

                    @Override public void onMessage(S message) {
                        long c0 = Meter.allocNow(), m0 = System.nanoTime();
                        from.bytesIn.addAndGet(Wire.sizeOf(message));
                        if (tel.payloads()) span.detail.put("result", Values.render(message));
                        from.chargeTo(span, Meter.allocNow() - c0, System.nanoTime() - m0);
                        super.onMessage(message);
                    }

                    @Override public void onClose(Status status, Metadata trailers) {
                        // Outside any bracket: network time is the simulated
                        // world's, not losim's overhead.
                        from.fleet().clock.spend(rttRefMs);

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
                        if (!effective.isOk())
                            tel.event(from.name,
                                      effective.getCode() == Status.Code.DEADLINE_EXCEEDED
                                              ? "rpc_timeout" : "rpc_error",
                                      "to", to, "method", method, "call", span.id,
                                      "status", effective.getCode().name());
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
                if (crossZone) from.crossZoneBytes.addAndGet(bytes);
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
