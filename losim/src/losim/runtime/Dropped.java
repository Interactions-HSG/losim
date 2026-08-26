package losim.runtime;

import io.grpc.*;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import losim.trace.Telemetry;
import losim.trace.Values;

/**
 * A call whose message never arrives.
 *
 * <p>A dead machine, a partition and a lost packet look identical from here, and
 * that is realistic: nothing answers, and the caller finds out by running out of
 * time. Modelling any of them as an immediate {@code UNAVAILABLE} would turn the
 * most instructive failure in distributed systems — the one where you cannot tell
 * a slow peer from a gone one — into a tidy error code.
 *
 * <p>A call with no deadline waits five seconds and then gives up, so a scenario
 * that forgot to set one still terminates. The trace says which it was.
 */
final class Dropped<Q, S> extends ClientCall<Q, S> {

    /** What a caller that set no deadline waits before losim gives up on its behalf. */
    private static final long NO_DEADLINE_NS = TimeUnit.SECONDS.toNanos(5);

    private final Machine from;
    private final String to, method, reason;
    private final CallOptions opts;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Listener<S> listener;
    private Telemetry.Span span;

    Dropped(Machine from, String to, String method, CallOptions opts, String reason) {
        this.from = from; this.to = to; this.method = method;
        this.opts = opts; this.reason = reason;
    }

    @Override public void start(Listener<S> l, Metadata headers) {
        listener = l;
        Telemetry tel = from.tel();
        span = tel.open(from.name, "rpc", method, "to", to, "delivered", false, "why", reason);
        tel.event(from.name, "rpc_call", "to", to, "method", method, "call", span.id);
    }

    @Override public void request(int n) { }

    @Override public void sendMessage(Q m) {
        from.bytesOut.addAndGet(Wire.sizeOf(m));
        if (span != null && from.tel().payloads()) span.detail.put("arg", Values.render(m));
    }

    @Override public void cancel(String message, Throwable cause) {
        finish(Status.CANCELLED.withDescription(message).withCause(cause));
    }

    @Override public void halfClose() {
        Deadline d = opts.getDeadline();
        long waitNs = d == null ? NO_DEADLINE_NS : Math.max(0, d.timeRemaining(TimeUnit.NANOSECONDS));
        Runnable expire = () -> {
            from.fleet().clock.parkRealNanos(waitNs);
            finish(Status.DEADLINE_EXCEEDED.withDescription(to + " did not answer (" + reason + ")"));
        };
        // The call's own executor is preferred: for a blocking call it is the
        // caller's thread, which would be waiting regardless.
        Executor ex = opts.getExecutor();
        if (ex != null) ex.execute(expire);
        else from.fleet().waiting().execute(expire);
    }

    /** Exactly once, however the call ends — a span that never closes is a telemetry bug. */
    private void finish(Status status) {
        if (!closed.compareAndSet(false, true)) return;
        Telemetry tel = from.tel();
        tel.close(span, status.getCode().name(), "ms", Machine.round(span.grossMs()));
        tel.event(from.name, status.getCode() == Status.Code.DEADLINE_EXCEEDED
                        ? "rpc_timeout" : "rpc_error",
                  "to", to, "method", method, "call", span.id,
                  "status", status.getCode().name(), "why", reason);
        if (listener != null) listener.onClose(status, new Metadata());
    }
}
