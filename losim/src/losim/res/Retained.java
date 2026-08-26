package losim.res;

import java.lang.reflect.*;
import java.util.*;

/**
 * How much heap a machine is actually holding.
 *
 * S1 measures allocation exactly, but allocation counts garbage as well as
 * survivors, and it is the survivors that decide an out-of-memory. This walks
 * the object graph from a machine's own roots and sums what is reachable.
 *
 * Two rules make it work on a modern JDK:
 *
 *   - **application objects are reflected into.** They live in the unnamed
 *     module, so their fields are accessible without opening anything.
 *   - **JDK containers are modelled, not reflected into.** `java.util` is not
 *     open to us, and `setAccessible` on `HashMap.table` throws. Modelling their
 *     layout avoids needing `--add-opens` at all — which matters, because losim
 *     has to run identically in three places without launcher flags (D10).
 */
public final class Retained {

    /** Compressed oops: on by default below a 32 GiB heap, which is every case here. */
    static final boolean COOPS = Runtime.getRuntime().maxMemory() < 32L << 30;
    static final int REF    = COOPS ? 4 : 8;
    static final int HEADER = COOPS ? 12 : 16;
    static final int ARRAY_HEADER = HEADER + 4;

    static int align(long n) { return (int) ((n + 7) & ~7L); }

    public record Result(long bytes, int objects, long walkNanos, int refused) {}

    /** Sums the heap reachable from these roots, stopping wherever `boundary` says to. */
    public static Result of(Collection<?> roots, java.util.function.Predicate<Object> boundary) {
        long t0 = System.nanoTime();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        var stack = new ArrayDeque<Object>(roots);
        long bytes = 0; int objects = 0, refused = 0;

        while (!stack.isEmpty()) {
            Object o = stack.pop();
            if (o == null || !seen.add(o)) continue;
            if (boundary.test(o)) { refused++; continue; }
            objects++;

            Class<?> c = o.getClass();
            if (c.isArray()) {
                int len = Array.getLength(o);
                Class<?> et = c.getComponentType();
                bytes += align(ARRAY_HEADER + (long) len * widthOf(et));
                if (!et.isPrimitive())
                    for (int i = 0; i < len; i++) stack.push(Array.get(o, i));
                continue;
            }
            // Boxes and strings: known shapes, and reflecting into them is both
            // pointless and blocked.
            if (o instanceof String s) { bytes += align(HEADER + REF + 4) + align(ARRAY_HEADER + s.length()); continue; }
            if (isBox(c)) { bytes += align(HEADER + 8); continue; }

            if (o instanceof Map<?, ?> m)        { bytes += mapOverhead(m); pushAll(stack, m); continue; }
            if (o instanceof Collection<?> col)  { bytes += collectionOverhead(col); col.forEach(stack::push); continue; }

            if (isJdk(c)) { bytes += align(HEADER + 32); continue; }   // opaque, modelled coarsely

            bytes += shallowOf(c);
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try { f.setAccessible(true); stack.push(f.get(o)); }
                    catch (ReflectiveOperationException | RuntimeException e) { refused++; }
                }
        }
        return new Result(bytes, objects, System.nanoTime() - t0, refused);
    }

    private static void pushAll(Deque<Object> s, Map<?, ?> m) {
        m.forEach((k, v) -> { s.push(k); s.push(v); });
    }

    /** The container itself: table, nodes, and the object holding them. */
    private static long mapOverhead(Map<?, ?> m) {
        int n = m.size();
        int cap = Integer.highestOneBit(Math.max(16, n * 2 - 1)) * 2;
        long node = align(HEADER + 4 + REF * 3L);          // hash + key + value + next
        return align(HEADER + 4L * 4 + REF * 3)            // the map object
             + align(ARRAY_HEADER + (long) cap * REF)      // the table
             + node * n;
    }

    private static long collectionOverhead(Collection<?> c) {
        int n = c.size();
        return align(HEADER + 8 + REF) + align(ARRAY_HEADER + (long) Math.max(10, n) * REF);
    }

    private static long shallowOf(Class<?> c) {
        long size = HEADER;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                size += widthOf(f.getType());
            }
        return align(size);
    }

    static int widthOf(Class<?> t) {
        if (!t.isPrimitive()) return REF;
        if (t == boolean.class || t == byte.class) return 1;
        if (t == char.class || t == short.class)   return 2;
        if (t == int.class || t == float.class)    return 4;
        return 8;
    }

    static boolean isBox(Class<?> c) {
        return c == Integer.class || c == Long.class || c == Double.class || c == Float.class
            || c == Short.class || c == Byte.class || c == Character.class || c == Boolean.class;
    }

    static boolean isJdk(Class<?> c) {
        Module m = c.getModule();
        return m != null && m.isNamed() && m.getName().startsWith("java.");
    }
}
