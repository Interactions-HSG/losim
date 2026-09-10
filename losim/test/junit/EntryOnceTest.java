import static org.junit.jupiter.api.Assertions.*;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import dissaly.pb.JobGrpc;
import dissaly.pb.Result;
import dissaly.pb.Workload;
import dissaly.runtime.Machines;
import dissaly.time.Clock;
import dissaly.trace.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * dissaly.Job is losim's way in, and it is used once.
 *
 * <p>Neither half is checkable when the file is read: a node can only make this
 * call while running, and the header marking losim's own call is one line of
 * {@link MetadataUtils} for anybody holding a channel. So there are two guards,
 * and this asserts both — including the case where the header is forged, because
 * a guard that only holds against callers who did not try is not a guard.
 *
 * <p>What it costs to get wrong: a node calling {@code Run} would start a second
 * simulation inside the first, and both would be measured as one. The duration,
 * the bill and every fitted law would be over two workloads reported as one, and
 * nothing in the trace would say which.
 */
class EntryOnceTest {

    /** An entry that answers, so a refusal is the guard's and not the Job's. */
    static final class Entry extends JobGrpc.JobImplBase {
        @Override public void run(Workload work, StreamObserver<Result> out) {
            out.onNext(Result.newBuilder().putAnswer("ran", "yes").build());
            out.onCompleted();
        }
    }

    /** The header losim puts on its own call, spelled the way anyone could spell it. */
    private static final Metadata.Key<String> OUTSIDE =
            Metadata.Key.of("losim-outside", Metadata.ASCII_STRING_MARSHALLER);

    private static Machines cluster() {
        return new Machines(new Telemetry(new Clock(1.0, 1.0), Telemetry.Level.FULL));
    }

    private static Metadata forged() {
        var md = new Metadata();
        md.put(OUTSIDE, "1");
        return md;
    }

    @Test
    @DisplayName("nothing inside the system calls it")
    void insideIsRefused() {
        try (var machines = cluster()) {
            machines.machine("entry", "m5.large", "z")
                    .serves(Entry::new, JobGrpc.SERVICE_NAME, "test:1");
            var worker = machines.machine("worker", "m5.large", "z");

            var stub = JobGrpc.newBlockingStub(worker.channelTo("entry"));
            var e = assertThrows(StatusRuntimeException.class,
                    () -> stub.run(Workload.getDefaultInstance()));

            assertEquals(Status.Code.FAILED_PRECONDITION, e.getStatus().getCode());
            assertTrue(String.valueOf(e.getStatus().getDescription())
                            .contains("nothing inside the system makes it"),
                    "the refusal has to say which mistake was made: " + e.getStatus());
        }
    }

    @Test
    @DisplayName("and forging the header buys one call, not two")
    void twiceIsRefused() {
        try (var machines = cluster()) {
            machines.machine("entry", "m5.large", "z")
                    .serves(Entry::new, JobGrpc.SERVICE_NAME, "test:1");
            var worker = machines.machine("worker", "m5.large", "z");

            var stub = JobGrpc.newBlockingStub(worker.channelTo("entry"))
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(forged()));

            // The header is spoofable, so it decides nothing on its own.
            assertEquals("yes", stub.run(Workload.getDefaultInstance())
                    .getAnswerOrDefault("ran", ""),
                    "a call carrying the header is losim's as far as anyone can tell");

            var e = assertThrows(StatusRuntimeException.class,
                    () -> stub.run(Workload.getDefaultInstance()));
            assertEquals(Status.Code.FAILED_PRECONDITION, e.getStatus().getCode());
            assertTrue(String.valueOf(e.getStatus().getDescription())
                            .contains("has already been called"),
                    "the counter is what makes the guard true: " + e.getStatus());
        }
    }

    @Test
    @DisplayName("Load and Run are counted apart — one each, not one between them")
    void loadAndRunAreSeparate() {
        try (var machines = cluster()) {
            machines.machine("entry", "m5.large", "z")
                    .serves(Entry::new, JobGrpc.SERVICE_NAME, "test:1");
            var worker = machines.machine("worker", "m5.large", "z");

            var stub = JobGrpc.newBlockingStub(worker.channelTo("entry"))
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(forged()));

            var e = assertThrows(StatusRuntimeException.class,
                    () -> stub.load(dissaly.pb.Input.newBuilder().setUnit("line").setCount(1).build()),
                    "this Entry does not implement Load, so it must fail as UNIMPLEMENTED");
            assertEquals(Status.Code.UNIMPLEMENTED, e.getStatus().getCode(),
                    "a Load that got through must not have been refused as a second entry");

            assertEquals("yes", stub.run(Workload.getDefaultInstance())
                    .getAnswerOrDefault("ran", ""),
                    "Load spent Run's one entry, so the two are counted together");
        }
    }
}
