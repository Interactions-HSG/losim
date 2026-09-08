package losim.runtime;

import io.grpc.*;
import losim.api.Ambient;

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
 * per-unit sleep. Those are the simulated program's time, not losim's, and
 * subtracting them would report a handler as faster than it was asked to be.
 */
final class ServerSide implements ServerInterceptor {

    private final Machine node;

    ServerSide(Machine node) { this.node = node; }

    /**
     * The call this one is part of, as the caller wrote it into the header.
     *
     * <p>Read from the header rather than from the ambient context: this thread
     * belongs to another node and has no context from the caller, and reading it
     * from the ambient one would hang every server span off the root, leaving no
     * distributed call stack at all (D8 rule 2).
     */
    private static long parentOf(Metadata headers) {
        String parent = headers.get(Machines.PARENT);
        try { return parent == null ? 0 : Long.parseLong(parent); }
        catch (NumberFormatException ignored) { return 0; }
    }

    @Override public <Q, S> ServerCall.Listener<Q> interceptCall(
            ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {

        long a0 = Meter.allocNow(), t0 = System.nanoTime();

        final Telemetry tel = node.tel();
        final String full = call.getMethodDescriptor().getFullMethodName();
        final String method = Wire.dotted(full);
        final Cost takes = node.takenBy(full);

        // losim.Job is losim's own way into the system, and it is used once.
        //
        // Neither half of that is checkable at load: a node can only make this call
        // while running, and the header that marks losim's own call can be attached
        // by anybody holding a channel — one line of MetadataUtils. So the header is
        // what lets the refusal say which mistake was made, and the counter is what
        // makes it true.
        //
        // Before the draws below, so that a call which is going to be refused does
        // not consume a draw from the seed's stream and move every later failure.
        if (full.startsWith(losim.pb.JobGrpc.SERVICE_NAME + "/")) {
            String no = null;
            if (headers.get(Machines.OUTSIDE) == null)
                no = method + " is losim's call into the system, and nothing inside the"
                   + " system makes it: the simulation it starts is the one already"
                   + " running. Whatever this node wanted to do again, it does by"
                   + " calling its own services.";
            else if (!node.machines().firstEntry(full))
                no = method + " has already been called. losim loads once and runs once,"
                   + " and a second entry would be measured into the first — one"
                   + " duration and one bill over two workloads.";
            if (no != null) {
                node.charge(Meter.allocNow() - a0, System.nanoTime() - t0);
                call.close(Status.FAILED_PRECONDITION.withDescription(no), new Metadata());
                return new ServerCall.Listener<Q>() {};
            }
        }

        // What the simulation said goes wrong with this rpc on this node, drawn
        // once for this call. Both draws happen here, before anything else, so
        // that a call which is going to be refused is refused without opening a
        // span or occupying a thread — the caller's whole experience of it is a
        // status, which is what makes it a failure worth handling rather than a
        // slow answer.
        double slower = 1;
        for (Machine.Drawn f : node.failuresOn(full)) {
            if (!f.fires()) continue;
            switch (f.spec().kind()) {
                case STATUS -> {
                    // Resolved here rather than at load: the loader runs in the
                    // console's server too, which has no gRPC on its classpath.
                    var code = io.grpc.Status.Code.valueOf(f.spec().status());
                    tel.event(node.name, "rpc_failure", "kind", "status", "method", method,
                              "status", f.spec().status(), "call", parentOf(headers));
                    node.charge(Meter.allocNow() - a0, System.nanoTime() - t0);
                    call.close(code.toStatus().withDescription(
                            "the simulation fails " + method + " on " + node.name
                            + " one call in " + f.spec().perCalls()), new Metadata());
                    return new ServerCall.Listener<Q>() {};
                }
                case SLOW -> slower *= f.spec().factor();
                case DROP -> { }                  // the caller's side; it never got here
            }
        }
        // Multiplied into the sleeps rather than into the node's own factor: a
        // degraded node is slow at everything, and this is one call path being
        // slow, which is the thing a design cannot route around by picking another
        // peer.
        final double slow = slower;
        if (slow > 1) tel.event(node.name, "rpc_failure", "kind", "slow", "method", method,
                                "factor", slow, "call", parentOf(headers));

        // What the caller allowed, read on arrival and in reference milliseconds.
        //
        // The client side can only ever check a deadline against the *fixed* part
        // of a cost: `refNsPerUnit` is not knowable before the handler runs,
        // because the handler is what declares the count. So a deadline set
        // comfortably above `refMs` and hopelessly below the real total — 600 refMs
        // against 2 + 0.02 per unit, about 780 at six million — times out with
        // nothing said, which is the shape of the mistake people actually make.
        //
        // Here that is knowable. The callee has the count by the time it answers,
        // and gRPC hands it the caller's deadline. `timeRemaining` rather than the
        // original figure because it is the truer number: it is what this handler
        // actually had once the request had crossed the network.
        final Deadline allowed = Context.current().getDeadline();
        final Double deadlineRefMs = allowed == null ? null
                : allowed.timeRemaining(java.util.concurrent.TimeUnit.NANOSECONDS)
                        * node.machines().clock.kTime() / 1e6;

        long parentId = parentOf(headers);
        final boolean outside = headers.get(Machines.OUTSIDE) != null;

        // As a number, because the client side writes it as one: the same call has to
        // be the same value on both sides of it, or nothing downstream can join them.
        final long callId = parentId;
        final Telemetry.Span span = tel.openUnder(parentId, node.name, "handler", method,
                "call", callId);

        var recording = new ForwardingServerCall.SimpleForwardingServerCall<Q, S>(call) {
            @Override public void sendMessage(S message) {
                // The variable part of the cost is paid before the response
                // leaves, not after: a caller waits for work that has not
                // finished, and by now the handler has said how much there was.
                if (takes != null && takes.perUnitRefMs() > 0) {
                    long n = span.units.get();
                    if (n > 0) node.machines().clock
                            .spend(takes.perUnitRefMs() * n * node.effectiveFactor() * slow);
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

                    // In close() and not in sendMessage(), because this has to hold
                    // for a call that never answered. The server is not told that
                    // the caller gave up — over the in-process transport it runs to
                    // completion and closes OK — so close() is the one place reached
                    // whether the answer arrived in time, late, or not at all.
                    if (takes != null && deadlineRefMs != null) {
                        long n = span.units.get();
                        double declared = takes.fixedRefMs();
                        if (n > 0 && takes.perUnitRefMs() > 0) declared += takes.perUnitRefMs() * n;
                        declared *= node.effectiveFactor() * slow;
                        span.detail.put("declaredRefMs", Machine.round(declared));
                        span.detail.put("deadlineRefMs", Machine.round(deadlineRefMs));
                        // Sound in one direction only, and that is the useful one. A
                        // declared cost is *slept* and never subtracted — simulatedDuration: can
                        // make a run longer and never shorter — so it is a lower bound
                        // on what the handler actually took, and a declared cost above
                        // the deadline is impossible rather than unlikely. The converse
                        // does not hold: a declared cost under the deadline says
                        // nothing, because the handler's own work is still to come on
                        // top of it.
                        //
                        // Strictly greater: a cost that exactly equals its deadline is
                        // not a mistake, it is a tight budget, and calling it impossible
                        // would be wrong.
                        if (declared > deadlineRefMs) span.detail.put("unmeetable", true);
                    }
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
                // Weighed unless it came from outside the system — see
                // Machines.OUTSIDE. Nothing crossed a wire to get here, and the
                // argument is one this node built itself.
                if (!outside) {
                    long bytes = Wire.sizeOf(message);
                    node.bytesIn.addAndGet(bytes);
                    span.detail.put("inBytes", bytes);
                }
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
                tel.event(node.name, "handler_start", "method", method, "call", callId);
                node.chargeTo(span, Meter.allocNow() - b0, System.nanoTime() - n0);

                if (takes != null && takes.fixedRefMs() > 0)
                    node.machines().clock.spend(takes.fixedRefMs() * node.effectiveFactor() * slow);

                // The handler runs inside this call, on this thread, and so does
                // some of losim's own work — `sendMessage` and `close` are both
                // reached from inside it. Both marks are taken here and the
                // charged bytes between them subtracted, which leaves what the
                // *program* allocated: the same subtraction `programMs` makes,
                // in the other unit.
                //
                // The two marks are deliberately *not* a charged region of their
                // own. They cost 57 ns the pair, measured, against a
                // `UNSEEN_NANOS_PER_REGION` of about 70 — so bracketing them
                // would hand the program back more than taking them ever cost
                // it, which is the same error as not correcting at all, in the
                // other direction.
                long h0 = Meter.allocNow(), l0 = span.losimBytes.get();
                try {
                    super.onHalfClose();
                } finally {
                    long mine = Meter.allocNow() - h0;
                    long ours = span.losimBytes.get() - l0;
                    span.allocBytes.addAndGet(Math.max(0, mine - ours));
                }
            }

            @Override public void onComplete() { finish("OK"); super.onComplete(); }
            @Override public void onCancel()   { finish("CANCELLED"); super.onCancel(); }

            private void finish(String status) {
                long b0 = Meter.allocNow(), n0 = System.nanoTime();
                node.inflight.decrementAndGet();
                node.handled.incrementAndGet();
                // On the span before it closes, so every handler in the trace
                // carries what it allocated next to what it took.
                span.detail.put("allocBytes", span.allocBytes.get());
                tel.close(span, status);
                tel.event(node.name, "handler_end", "method", method, "call", callId,
                          "status", status,
                          "ms", Machine.round(span.programMs(tel.kTime())),
                          "grossMs", Machine.round(span.grossMs()),
                          "allocBytes", span.allocBytes.get(),
                          "arg", span.detail.get("arg"),
                          "result", span.detail.get("result"));
                node.chargeTo(span, Meter.allocNow() - b0, System.nanoTime() - n0);
            }
        };
        node.chargeTo(span, Meter.allocNow() - a1, System.nanoTime() - t1);
        return listener;
    }
}
