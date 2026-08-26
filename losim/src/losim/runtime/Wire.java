package losim.runtime;

import com.google.protobuf.Message;

/**
 * How many bytes a message really costs to send.
 *
 * <p>gRPC's in-process transport does <b>not</b> serialize: it hands the receiver
 * the same object reference. Protobuf messages are immutable, so passing by
 * reference is otherwise harmless — but it means a byte count can never be
 * inferred from the transport. It has to come from marshalling the message
 * explicitly, here, plus the framing gRPC would have put around it.
 */
public final class Wire {
    private Wire() {}

    /** One compressed-flag byte and a four-byte length prefix, per message. */
    public static final int FRAMING_BYTES = 5;

    /** Serialized size plus framing, or 0 for anything that is not a protobuf message. */
    public static long sizeOf(Object m) {
        return (m instanceof Message p) ? p.getSerializedSize() + FRAMING_BYTES : 0;
    }

    /** gRPC writes {@code Service/Method}; every view downstream splits on a dot (D9). */
    public static String dotted(String fullMethodName) {
        return fullMethodName.replace('/', '.');
    }
}
