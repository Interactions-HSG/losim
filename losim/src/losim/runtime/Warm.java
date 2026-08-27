package losim.runtime;

import com.google.protobuf.Empty;
import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import losim.time.Clock;
import losim.trace.Telemetry;

/**
 * Pays the JVM's first-call bill before the clock starts.
 *
 * <p>The first gRPC call a JVM makes is enormously more expensive than every
 * call after it — class loading down the whole stack, the interceptor chain, the
 * marshallers, and a cold JIT on all of it. Measured on a tour scenario: a
 * handler declaring {@code @Takes(refMs = 5)} was billed <b>320 refMs</b> on the
 * first call and 6 to 9 refMs on every call after. The same scenario run three
 * times in one JVM pays it once:
 *
 * <pre>
 *   run 1  handlers refMs: 309, 8
 *   run 2  handlers refMs:   9, 9
 *   run 3  handlers refMs:   8, 9
 * </pre>
 *
 * <p>That is a measurement bug, and a bad one, because it lands somewhere it does
 * real damage. It inflates the first call of every run by a factor nobody chose,
 * which distorts the film — the first message on screen takes half the run and
 * every later one is a flicker — and it lands in the fixed term {@code c} of
 * {@code demand = c + a·n^β}, which the scale engine then extrapolates (D13).
 *
 * <p>So it is paid here, once per JVM, on a throwaway fleet of losim's own that
 * appears in no trace, before {@link Fleet#begin()} zeroes the clock. Setup
 * belongs to no scenario.
 *
 * <p><b>What it does not fix.</b> This warms losim's path, not the student's
 * types: after warming, the first handler is around 50 refMs rather than 320,
 * and the rest is JIT on their own generated classes, which cannot be warmed
 * without calling their handler — and calling a student's handler with a
 * fabricated request, before their job has started, is not something losim may
 * do. Serializing their messages without calling anything was tried and is worth
 * 2 ms and no improvement, so it is not done.
 */
final class Warm {

    private Warm() {}

    /**
     * A method that exists only to be called before anything is being measured.
     *
     * <p>{@code Empty} rather than a losim-specific message, so no {@code .proto}
     * of losim's own has to be generated and shipped to make this work.
     */
    private static final MethodDescriptor<Empty, Empty> TOUCH =
            MethodDescriptor.<Empty, Empty>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("losim.Warm/Touch")
                    .setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
                    .build();

    /**
     * How many round trips. Five, because it is measured: one leaves the first
     * handler at ~67 refMs, five at ~47, and thirty and a hundred are no better
     * than five while costing proportionally more.
     */
    private static final int ROUNDS = 5;

    private static volatile boolean done;

    /**
     * Warms once per JVM. Subsequent calls return immediately.
     *
     * <p>Never throws. A warm-up that failed costs a slow first call, which is
     * the situation this exists to improve rather than a situation it may create:
     * refusing to run a scenario because the optimisation did not work would be
     * strictly worse than the bug.
     */
    static void once() {
        if (done) return;
        synchronized (Warm.class) {
            if (done) return;
            done = true;                       // set first: try once, never in a loop
            try {
                warm();
            } catch (Throwable ignored) {
                // Deliberately swallowed. See above.
            }
        }
    }

    private static void warm() throws Exception {
        // kTime 1 and a correction of 1: nothing here is measured, and
        // Clock.measureCorrection() is a measurement of its own that the real run
        // does for itself. Doing it here too would cost 400 ms for nothing.
        var tel = new Telemetry(new Clock(1, 1.0), Telemetry.Level.FULL);
        var net = new Net(0L).latency(0, 0).jitter(0).loss(0);
        try (var fleet = new Fleet(tel, net)) {
            BindableService touch = () -> ServerServiceDefinition.builder("losim.Warm")
                    .addMethod(TOUCH, ServerCalls.asyncUnaryCall(
                            (Empty q, StreamObserver<Empty> out) -> {
                                out.onNext(Empty.getDefaultInstance());
                                out.onCompleted();
                            }))
                    .build();
            Machine caller = fleet.machine("losim-warm-a", "m5.large", "eu-central-1a");
            fleet.machine("losim-warm-b", "m5.large", "eu-central-1a").serving(touch);
            caller.serving();
            fleet.begin();
            for (int i = 0; i < ROUNDS; i++)
                ClientCalls.blockingUnaryCall(caller.channelTo("losim-warm-b"), TOUCH,
                        CallOptions.DEFAULT, Empty.getDefaultInstance());
        }
    }
}
