package losim.runtime;

import io.grpc.BindableService;
import io.grpc.ServerMethodDefinition;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import losim.api.Cost;

/**
 * Which declared cost belongs to which gRPC method.
 *
 * <p>gRPC knows a method as {@code losim.wc.Worker/Map}; the student wrote
 * {@code @Cost(refMs = 2) protected Counts map(Chunk c)}. The bridge is
 * grpc-java's own naming rule — an rpc {@code Map} becomes a Java method
 * {@code map} — applied to whatever the service actually registered, so the
 * annotation is found on the adapter, on the generated base, or on any
 * superclass in between.
 *
 * <p>An unannotated method costs nothing, which is the right default: a handler
 * that has not been costed should run at whatever speed it runs, not at a made-up
 * one.
 */
final class Costs {
    private Costs() {}

    static Map<String, Cost> of(BindableService service) {
        var out = new HashMap<String, Cost>();
        Class<?> impl = service.getClass();
        for (ServerMethodDefinition<?, ?> m : service.bindService().getMethods()) {
            String full = m.getMethodDescriptor().getFullMethodName();
            int slash = full.indexOf('/');
            if (slash < 0) continue;
            String bare = full.substring(slash + 1);
            String javaName = Character.toLowerCase(bare.charAt(0)) + bare.substring(1);
            Cost c = find(impl, javaName);
            if (c != null) out.put(full, c);
        }
        return out;
    }

    private static Cost find(Class<?> from, String name) {
        for (Class<?> k = from; k != null && k != Object.class; k = k.getSuperclass())
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Cost c = m.getAnnotation(Cost.class);
                if (c != null) return c;
            }
        return null;
    }
}
