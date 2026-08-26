package losim.runtime;

import io.grpc.*;
import java.util.List;

/**
 * Tries again, when the scenario said to and the schema allowed it.
 *
 * <p>This sits <b>outside</b> {@link ClientSide} on the channel, so every attempt
 * is a genuinely separate call: its own span, its own byte count, its own draw
 * against loss and its own look at whether the callee is still alive. A retry that
 * reused the first attempt's accounting would make a design that retries look
 * cheaper than one that does not, which is the opposite of the truth.
 *
 * <p>Only unary calls are retried. Replaying a stream means buffering it, and a
 * buffer whose size nobody declared is its own failure mode; a streaming call that
 * fails is reported, not repeated.
 */
final class Retrying implements ClientInterceptor {

    private final Machine from;
    private final List<Retry> policies;

    Retrying(Machine from, List<Retry> policies) { this.from = from; this.policies = policies; }

    private Retry policyFor(MethodDescriptor<?, ?> md) {
        for (Retry r : policies) if (r.matches(md)) return r;
        return null;
    }

    @Override public <Q, S> ClientCall<Q, S> interceptCall(
            MethodDescriptor<Q, S> md, CallOptions opts, Channel next) {
        Retry policy = policyFor(md);
        if (policy == null || policy.attempts() <= 1
            || md.getType() != MethodDescriptor.MethodType.UNARY)
            return next.newCall(md, opts);
        return new Attempts<>(md, opts, next, policy);
    }

    /** One logical call, made up to {@code attempts} times. */
    private final class Attempts<Q, S> extends ClientCall<Q, S> {

        private final MethodDescriptor<Q, S> md;
        private final CallOptions opts;
        private final Channel channel;
        private final Retry policy;

        private Listener<S> caller;
        private Metadata headers;
        private Q request;
        private int requested;
        private int attempt;
        private ClientCall<Q, S> current;
        private boolean delivered;          // a message arrived, so this is not a retry candidate
        private volatile boolean cancelled;

        Attempts(MethodDescriptor<Q, S> md, CallOptions opts, Channel channel, Retry policy) {
            this.md = md; this.opts = opts; this.channel = channel; this.policy = policy;
        }

        @Override public void start(Listener<S> listener, Metadata h) {
            this.caller = listener;
            this.headers = h;
        }

        @Override public void request(int n) {
            requested += n;
            if (current != null) current.request(n);
        }

        @Override public void sendMessage(Q message) { this.request = message; }

        @Override public void cancel(String message, Throwable cause) {
            cancelled = true;
            if (current != null) current.cancel(message, cause);
        }

        @Override public void halfClose() { fire(); }

        private void fire() {
            attempt++;
            current = channel.newCall(md, opts);
            current.start(new Listener<S>() {
                @Override public void onHeaders(Metadata h) { caller.onHeaders(h); }

                @Override public void onMessage(S message) {
                    delivered = true;
                    caller.onMessage(message);
                }

                @Override public void onReady() { caller.onReady(); }

                @Override public void onClose(Status status, Metadata trailers) {
                    boolean again = !status.isOk() && !delivered && !cancelled
                                 && attempt < policy.attempts()
                                 && retryable(status);
                    if (!again) {
                        if (attempt > 1)
                            from.tel().event(from.name, "retry_done",
                                    "method", Wire.dotted(md.getFullMethodName()),
                                    "attempts", attempt, "status", status.getCode().name());
                        caller.onClose(status, trailers);
                        return;
                    }
                    double backoff = policy.backoffBefore(attempt + 1);
                    from.tel().event(from.name, "retry",
                            "method", Wire.dotted(md.getFullMethodName()),
                            "attempt", attempt, "of", policy.attempts(),
                            "after", status.getCode().name(),
                            "backoffRefMs", Machine.round(backoff),
                            "unsafe", policy.unsafe() ? true : null);
                    from.fleet().clock.spend(backoff);
                    fire();
                }
            }, copy(headers));
            current.request(Math.max(2, requested));
            current.sendMessage(request);
            current.halfClose();
        }

        /**
         * A failure worth repeating.
         *
         * <p>Nothing answered, the callee was overloaded, or the deadline ran out.
         * An {@code INVALID_ARGUMENT} will be invalid the second time too.
         */
        private boolean retryable(Status s) {
            return switch (s.getCode()) {
                case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED, UNKNOWN -> true;
                default -> false;
            };
        }

        /** Each attempt needs its own headers: the previous one had a span id written into it. */
        private Metadata copy(Metadata h) {
            var m = new Metadata();
            m.merge(h);
            m.discardAll(Fleet.PARENT);
            return m;
        }
    }
}
