package losim.net;

/** Encodes a message to bytes. The length is the wire size the latency model uses. */
public interface Codec {
    String name();
    byte[] encode(Object message);
    default int serializedSize(Object message) { return encode(message).length; }
}
