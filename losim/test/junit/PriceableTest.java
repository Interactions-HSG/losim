import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.Empty;
import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import losim.res.InstanceCatalog;
import losim.runtime.Fleet;
import losim.time.Clock;
import losim.trace.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two shapes of gRPC losim will not put a number on.
 *
 * <p>Both are refused rather than flagged, and the distinction is the whole point.
 * Everything the verifier catches yields a wrong number <i>beside a marker saying
 * it is wrong</i>. These two yield a wrong number that looks right — a streaming
 * call whose declared cost is multiplied by the number of messages it happened to
 * send, and a call carrying somebody else's marshaller that weighs nothing at all
 * — and a law fitted to either comes out smooth, confident and false.
 *
 * <p>Built by hand rather than from a {@code .proto}, deliberately: what is under
 * test is the check on a {@code MethodDescriptor}, and a schema would put a code
 * generator between the test and the thing it is testing.
 */
class PriceableTest {

    /** A marshaller that is nobody's protobuf — the shape a JSON or byte-array one has. */
    private static final MethodDescriptor.Marshaller<String> TEXT =
            new MethodDescriptor.Marshaller<>() {
                @Override public InputStream stream(String value) {
                    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
                }
                @Override public String parse(InputStream stream) { return ""; }
            };

    private static Fleet fleet() {
        return new Fleet(new Telemetry(new Clock(1, 1.0), Telemetry.Level.OFF));
    }

    private static BindableService serving(MethodDescriptor<Empty, Empty> md) {
        return () -> ServerServiceDefinition.builder("lab.Thing")
                .addMethod(md, ServerCalls.asyncUnaryCall(
                        (Empty q, StreamObserver<Empty> out) -> out.onCompleted()))
                .build();
    }

    private static MethodDescriptor.Builder<Empty, Empty> protoMethod(String name) {
        return MethodDescriptor.<Empty, Empty>newBuilder()
                .setFullMethodName("lab.Thing/" + name)
                .setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()));
    }

    @Test
    @DisplayName("a unary protobuf method is served without complaint")
    void unaryIsFine() throws Exception {
        try (Fleet f = fleet()) {
            var m = f.machine("srv", "m5.large", "z");
            assertDoesNotThrow(() -> m.serves(
                    () -> serving(protoMethod("Do")
                            .setType(MethodDescriptor.MethodType.UNARY).build()),
                    "thing.yaml:9"));
        }
    }

    @Test
    @DisplayName("every streaming shape is refused, named, and told why")
    void streamingIsRefused() throws Exception {
        for (var type : new MethodDescriptor.MethodType[]{
                MethodDescriptor.MethodType.SERVER_STREAMING,
                MethodDescriptor.MethodType.CLIENT_STREAMING,
                MethodDescriptor.MethodType.BIDI_STREAMING}) {
            try (Fleet f = fleet()) {
                var m = f.machine("srv", "m5.large", "z");
                var e = assertThrows(IllegalArgumentException.class,
                        () -> m.serves(() -> serving(protoMethod("Watch")
                                .setType(type).build()), "thing.yaml:9"));
                assertTrue(e.getMessage().startsWith("thing.yaml:9: "),
                        () -> "carries the runs: line it belongs to, so it reads like every "
                              + "other refusal the loader gives — " + e.getMessage());
                assertTrue(e.getMessage().contains("lab.Thing.Watch"),
                        () -> "names the method, not just the service — " + e.getMessage());
                assertTrue(e.getMessage().contains("refNsPerUnit"),
                        () -> "says which part of the cost model cannot hold, rather than "
                              + "that streaming is unsupported — " + e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("a marshaller that is not protobuf is refused, because it would weigh nothing")
    void ownMarshallerIsRefused() throws Exception {
        try (Fleet f = fleet()) {
            var m = f.machine("srv", "m5.large", "z");
            var e = assertThrows(IllegalArgumentException.class, () -> m.serves(
                    () -> (BindableService) () -> ServerServiceDefinition.builder("lab.Thing")
                            .addMethod(MethodDescriptor.<String, String>newBuilder()
                                    .setType(MethodDescriptor.MethodType.UNARY)
                                    .setFullMethodName("lab.Thing/Say")
                                    .setRequestMarshaller(TEXT)
                                    .setResponseMarshaller(TEXT)
                                    .build(),
                                    ServerCalls.asyncUnaryCall(
                                            (String q, StreamObserver<String> out) -> out.onCompleted()))
                            .build(),
                    "thing.yaml:9"));
            assertTrue(e.getMessage().contains("lab.Thing.Say"), e::getMessage);
            assertTrue(e.getMessage().contains("bill"),
                    () -> "says what it would cost you — a call that weighs nothing bills "
                          + "nothing — rather than naming a type — " + e.getMessage());
        }
    }

    @Test
    @DisplayName("losim's own warm-up descriptor stays priceable, schema descriptor or not")
    void handBuiltProtobufIsStillProtobuf() throws Exception {
        // Warm.TOUCH is built by hand and carries no schema descriptor while being
        // perfectly ordinary protobuf. Checking the schema rather than the
        // marshaller would disable the JVM warm-up — and silently, because Warm
        // swallows everything it throws.
        var md = protoMethod("Touch").setType(MethodDescriptor.MethodType.UNARY).build();
        assertNull(md.getSchemaDescriptor(), "the case that would have been missed");
        try (Fleet f = fleet()) {
            var m = f.machine("srv", "m5.large", "z");
            assertDoesNotThrow(() -> m.serves(() -> serving(md), ""));
        }
        assertNotNull(InstanceCatalog.get("m5.large"));   // the fleet really was built
    }
}
