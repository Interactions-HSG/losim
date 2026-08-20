package losim.net;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Style A: schema-less encoding for plain Java records.
 *
 * Field NAMES go on the wire, because without a schema the receiver has no other
 * way to know what it is looking at. That is exactly why it is bigger than
 * protobuf, and the size difference is the point.
 */
public final class RecordCodec implements Codec {

    public static final RecordCodec INSTANCE = new RecordCodec();

    @Override public String name() { return "record"; }

    @Override public byte[] encode(Object message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(message, out);
        return out.toByteArray();
    }

    private void write(Object v, ByteArrayOutputStream out) {
        switch (v) {
            case null -> out.write(0);
            case String s -> { out.write(1); bytes(s.getBytes(StandardCharsets.UTF_8), out); }
            case Integer i -> { out.write(2); fixed(i, 4, out); }
            case Long l -> { out.write(3); fixed(l, 8, out); }
            case Boolean b -> { out.write(4); out.write(b ? 1 : 0); }
            case Double d -> { out.write(5); fixed(Double.doubleToLongBits(d), 8, out); }
            case byte[] b -> { out.write(6); bytes(b, out); }
            case Enum<?> e -> { out.write(7); bytes(e.name().getBytes(StandardCharsets.UTF_8), out); }
            case List<?> list -> {
                out.write(8); fixed(list.size(), 4, out);
                for (Object o : list) write(o, out);
            }
            case Map<?, ?> map -> {
                out.write(9); fixed(map.size(), 4, out);
                for (Map.Entry<?, ?> e : map.entrySet()) { write(e.getKey(), out); write(e.getValue(), out); }
            }
            default -> {
                if (!v.getClass().isRecord())
                    throw new IllegalArgumentException("not encodable: " + v.getClass().getName()
                            + " (messages must be records, collections or primitives)");
                out.write(10);
                // the type name travels too — schema-less receivers need it
                bytes(v.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8), out);
                RecordComponent[] comps = v.getClass().getRecordComponents();
                fixed(comps.length, 4, out);
                for (RecordComponent rc : comps) {
                    bytes(rc.getName().getBytes(StandardCharsets.UTF_8), out);   // field NAME on the wire
                    try { var acc = rc.getAccessor(); acc.setAccessible(true); write(acc.invoke(v), out); }
                    catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                }
            }
        }
    }

    private static void bytes(byte[] b, ByteArrayOutputStream out) {
        fixed(b.length, 4, out);
        out.write(b, 0, b.length);
    }

    private static void fixed(long value, int n, ByteArrayOutputStream out) {
        for (int i = 0; i < n; i++) out.write((int) ((value >>> (8 * i)) & 0xFF));
    }
}
