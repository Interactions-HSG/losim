package losim.runtime;

import losim.api.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reflection plumbing: finds handlers, matches client methods to server methods. */
public final class Dispatch {
    private Dispatch() {}

    public record Handler(Program program, Method method, long costMs) {}

    public static Optional<Handler> messageHandler(Vm vm, Object payload) {
        for (Program p : vm.programs) {
            for (Method m : sorted(p.getClass().getMethods())) {
                if (!m.isAnnotationPresent(OnMessage.class)) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 3 && pt[0] == Ctx.class && pt[1] == VmRef.class
                        && pt[2].isInstance(payload)) {
                    m.setAccessible(true);
                    return Optional.of(new Handler(p, m, costOf(m)));
                }
            }
        }
        return Optional.empty();
    }

    public static List<Handler> terminateHandlers(Vm vm) {
        List<Handler> out = new ArrayList<>();
        for (Program p : vm.programs)
            for (Method m : sorted(p.getClass().getMethods()))
                if (m.isAnnotationPresent(OnTerminate.class) && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    out.add(new Handler(p, m, costOf(m)));
                }
        return out;
    }

    public static List<Handler> timerHandlers(Vm vm) {
        List<Handler> out = new ArrayList<>();
        for (Program p : vm.programs)
            for (Method m : sorted(p.getClass().getMethods()))
                if (m.isAnnotationPresent(OnTimer.class)) {
                    m.setAccessible(true);
                    out.add(new Handler(p, m, costOf(m)));
                }
        return out;
    }

    public static long costOf(Method m) {
        Cost c = m.getAnnotation(Cost.class);
        return c == null ? 0 : c.ms();
    }

    public static Class<?> serviceOf(Class<?> peerType) {
        ServiceOf s = peerType.getAnnotation(ServiceOf.class);
        if (s == null) throw new IllegalArgumentException(
                peerType.getName() + " is not a peer interface (missing @ServiceOf)");
        return s.value();
    }

    /**
     * The client method {@code map(Chunk)} maps to the server method
     * {@code map(Ctx, Chunk)} — the caller has no business supplying the
     * callee's context, so the signatures differ by exactly that first parameter.
     */
    public static Method serverMethod(Class<?> service, Method clientMethod) {
        for (Method m : sorted(service.getMethods())) {
            if (!m.getName().equals(clientMethod.getName())) continue;
            Class<?>[] sp = m.getParameterTypes();
            Class<?>[] cp = clientMethod.getParameterTypes();
            if (sp.length != cp.length + 1) continue;
            if (sp[0] != Ctx.class) continue;
            boolean ok = true;
            for (int i = 0; i < cp.length; i++) if (sp[i + 1] != cp[i]) { ok = false; break; }
            if (ok) { m.setAccessible(true); return m; }
        }
        throw new IllegalStateException("no server method on " + service.getSimpleName()
                + " matching client method " + clientMethod.getName());
    }

    private static Method[] sorted(Method[] ms) {
        Method[] copy = ms.clone();
        java.util.Arrays.sort(copy, (a, b) -> {
            int c = a.getName().compareTo(b.getName());
            if (c != 0) return c;
            return Integer.compare(a.getParameterCount(), b.getParameterCount());
        });
        return copy;
    }
}
