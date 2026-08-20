package losim.net;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Style B: protobuf-shaped encoding.
 *
 * Field NUMBERS instead of names, varints instead of fixed widths. Same messages
 * as {@link RecordCodec}, materially fewer bytes — which is what the schema buys.
 */
public final class ProtoCodec implements Codec {

    public static final ProtoCodec INSTANCE = new ProtoCodec();

    @Override public String name() { return "proto"; }

    @Override public byte[] encode(Object message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(message, out);
        return out.toByteArray();
    }

    private void write(Object v, ByteArrayOutputStream out) {
        switch (v) {
            case null -> varint(0, out);
            case String s -> lengthDelimited(s.getBytes(StandardCharsets.UTF_8), out);
            case Integer i -> varint(zigzag(i), out);
            case Long l -> varint(zigzag(l), out);
            case Boolean b -> varint(b ? 1 : 0, out);
            case Double d -> { for (int i = 0; i < 8; i++) out.write((int) ((Double.doubleToLongBits(d) >>> (8 * i)) & 0xFF)); }
            case byte[] b -> lengthDelimited(b, out);
            case Enum<?> e -> varint(e.ordinal(), out);
            case List<?> list -> {
                varint(list.size(), out);
                for (Object o : list) write(o, out);
            }
            case Map<?, ?> map -> {
                varint(map.size(), out);
                for (Map.Entry<?, ?> e : map.entrySet()) { write(e.getKey(), out); write(e.getValue(), out); }
            }
            default -> {
                if (!v.getClass().isRecord())
                    throw new IllegalArgumentException("not encodable: " + v.getClass().getName());
                RecordComponent[] comps = v.getClass().getRecordComponents();
                for (int i = 0; i < comps.length; i++) {
                    varint((i + 1) << 3, out);                 // field number, no name
                    try { var acc = comps[i].getAccessor(); acc.setAccessible(true); write(acc.invoke(v), out); }
                    catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                }
            }
        }
    }

    private static long zigzag(long v) { return (v << 1) ^ (v >> 63); }

    private static void lengthDelimited(byte[] b, ByteArrayOutputStream out) {
        varint(b.length, out);
        out.write(b, 0, b.length);
    }

    private static void varint(long value, ByteArrayOutputStream out) {
        long v = value;
        while ((v & ~0x7FL) != 0) { out.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        out.write((int) v);
    }
}
