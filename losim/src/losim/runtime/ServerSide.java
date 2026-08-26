package losim.runtime;

import io.grpc.*;
import losim.api.Ambient;
import losim.api.Takes;
import losim.res.Meter;
import losim.trace.Telemetry;
import losim.trace.Values;

/**
 * What losim does around a call the machine is serving: the declared cost, the
 * ambient context, and the record of what happened.
 *
 * <h2>Everything between the marks is losim's</h2>
 * This runs on the machine's own pool thread, between the two reads of
 * {@code getThreadAllocatedBytes} that measure that machine. So every region of
 * losim's own work is bracketed and charged to the machine's ledger, which
 * {@link Machine#allocatedBytes()} then subtracts (D13). gRPC's own work stays
 * outside the marks, because it is not losim's to give back.
 *
 * <p>Two things are pointedly <b>not</b> bracketed: the cost sleep and the
 * per-record sleep. Those are the simulated program's time, not losim's, and
 * subtracting them would report a handler as faster than it was asked to be.
 */
final class ServerSide implements ServerInterceptor {

    private final Machine node;

    ServerSide(Machine node) { this.node = node; }

    @Override public <Q, S> ServerCall.Listener<Q> interceptCall(
            ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {

        long a0 = Meter.allocNow(), t0 = System.nanoTime();

        final Telemetry tel = node.tel();
        final String full = call.getMethodDescriptor().getFullMethodName();
        final String method = Wire.dotted(full);
        final Takes takes = node.takenBy(full);

        // The parent arrives in a header, from a thread on another machine this
        // one has no context from. Reading it from the ambient context instead
        // would hang every server span off the root, and there would be no
        // distributed call stack at all (D8 rule 2).
        final String parent = headers.get(Fleet.PARENT);
        long parentId = 0;
        try { if (parent != null) parentId = Long.parseLong(parent); }
        catch (NumberFormatException ignored) { }

        final Telemetry.Span span = tel.openUnder(parentId, node.name, "handler", method,
                "call", parent == null ? "?" : parent);

        var recording = new ForwardingServerCall.SimpleForwardingServerCall<Q, S>(call) {
            @Override public void sendMessage(S message) {
                // The variable part of the cost is paid before the response
                // leaves, not after: a caller waits for work that has not
                // finished, and by now the handler has said how much there was.
                if (takes != null && takes.refNsPerRecord() > 0) {
                    long n = span.records.get();
                    if (n > 0) node.fleet().clock
                            .spend(takes.refNsPerRecord() * n / 1e6 * node.effectiveFactor());
                }
                long b0 = Meter.allocNow(), n0 = System.nanoTime();
                if (tel.payloads()) span.detail.put("result", Values.render(message));
                long bytes = Wire.sizeOf(message);
                node.bytesOut.addAndGet(bytes);
                span.detail.put("outBytes", bytes);
                node.chargeTo(span, Meter.allocNow() - b0, System.nanoTime() - n0);
                super.sendMessage(message);
            }

            // A call that failed has no result, so it must carry why instead —
            // otherwise the one call in the run worth looking at is the one
            // blank row (D8 rule 4).
            @Override public void close(Status status, Metadata trailers) {
                long b0 = Meter.allocNow(), n0 = System.nanoTime();
                if (tel.records()) {
                    span.detail.put("status", status.getCode().name());
                    if (!status.isOk())
                        span.detail.put("error", status.getDescription() == null
                                ? status.getCode().name() : status.getDescription());
                }
                node.chargeTo(span, Meter.allocNow() - b0, System.nanoTime() - n0);
                super.close(status, trailers);
            }
        };

        Context ctx = Context.current()
                .withValue(Ambient.MACHINE, node)
                .withValue(Telemetry.SPAN, span);
        node.chargeTo(span, Meter.allocNow() - a0, System.nanoTime() - t0);

        var delegate = Contexts.interceptCall(ctx, recording, headers, next);

        long a1 = Meter.allocNow(), t1 = System.nanoTime();
        var listener = new ForwardingServerCallListener
                .SimpleForwardingServerCallListener<Q>(delegate) {

            @Override public void onMessage(Q message) {
                long b0 = Meter.allocNow(), n0 = System.nanoTime();
                long bytes = Wire.sizeOf(message);
                node.bytesIn.addAndGet(bytes);
                span.detail.put("inBytes", bytes);
                if (tel.payloads()) span.detail.put("arg", Values.render(message));
                node.chargeTo(span, Meter.allocNow() - b0, System.nanoTime() - n0);
                super.onMessage(message);
            }

            /** The request is complete, so this is where the handler is about to run. */
            @Override public void onHalfClose() {
                // A frozen machine does not refuse: it holds the call, on its own
                // thread, and the caller cannot tell that from slowness.
                node.awaitThaw();

                long b0 = Meter.allocNow(), n0 = System.nanoTime();
                node.inflight.incrementAndGet();
                // handler_start goes out before the cost sleep and handler_end
                // after the handler returns, or every gantt block collapses (D9).
                tel.event(node.name, "handler_start", "method", method, "call", parent);
                node.chargeTo(span, Meter.allocNow() - b0, System.nanoTime() - n0);

                if (takes != null && takes.refMs() > 0)
                    node.fleet().clock.spend(takes.refMs() * node.effectiveFactor());

                super.onHalfClose();
            }

            @Override public void onComplete() { finish("OK"); super.onComplete(); }
            @Override public void onCancel()   { finish("CANCELLED"); super.onCancel(); }

            private void finish(String status) {
                long b0 = Meter.allocNow(), n0 = System.nanoTime();
                node.inflight.decrementAndGet();
                node.handled.incrementAndGet();
                tel.close(span, status);
                tel.event(node.name, "handler_end", "method", method, "call", parent,
                          "status", status,
                          "ms", Machine.round(span.programMs(tel.kTime())),
                          "grossMs", Machine.round(span.grossMs()),
                          "arg", span.detail.get("arg"),
                          "result", span.detail.get("result"));
                node.chargeTo(span, Meter.allocNow() - b0, System.nanoTime() - n0);
            }
        };
        node.chargeTo(span, Meter.allocNow() - a1, System.nanoTime() - t1);
        return listener;
    }
}
