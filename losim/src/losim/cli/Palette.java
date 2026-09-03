package losim.cli;

import java.io.IOException;
import java.io.File;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import losim.api.Job;

/**
 * What a system's code offers a machine.
 *
 * <p>A scenario places <b>classes</b> on machines. Until now the only way to know
 * which classes could be placed was to read the source, get the fully qualified
 * name right by hand, and find out at run time whether it was a service at all —
 * and the error for getting it wrong arrives after a build, a generate and a JVM
 * start. This reads the answer off the compiled classes instead, so a console can
 * offer the list rather than asking somebody to remember it.
 *
 * <p><b>Two names, and they are different on purpose.</b> A machine <i>runs</i> a
 * Java class ({@code lab.Shrinker}) and thereby <i>serves</i> the gRPC service
 * that class implements ({@code Thumbnailer}). The scenario names the first; the
 * trace reports the second; a job finds its peers by the second. Both are
 * reported here, together, so nobody has to hold the pair in their head.
 *
 * <p><b>Nothing of the student's is executed.</b> Classes are loaded without
 * initialising them and are never constructed: what a service offers is read off
 * the descriptor {@code protoc} generated, which is losim's own dependency rather
 * than anybody's submission. A console that had to instantiate a class to list it
 * would run a constructor — and a constructor that loops is a server that stops
 * answering.
 *
 * <p><b>gRPC is reached by name, never by import.</b> The server is started with
 * {@code losim.jar} alone; the gRPC jars belong to the lab and are only ever on a
 * run's classpath, in the JVM the run forks. So every grpc type here comes
 * through the lab's own loader and is used reflectively — importing one would
 * link this class against something the process listing it does not have, and the
 * symptom is a {@code NoClassDefFoundError} the moment somebody opens the page.
 */
public final class Palette {

    /**
     * One class a machine could run.
     *
     * @param cls       the Java class, fully qualified — what {@code runs:} takes
     * @param service   the bare gRPC service name — what the trace's {@code serves} reports
     * @param qualified the same service with its proto package, as gRPC names it on the wire
     * @param methods   what can be called on it, and what {@code retries:} names
     * @param source    the file it was written in, relative to the project, when it can be found
     */
    public record Service(String cls, String service, String qualified,
                          List<Method> methods, String source) {}

    /**
     * One rpc.
     *
     * @param idempotent whether the {@code .proto} declared it safe to run twice.
     *                   Carried because a retry policy on a method that did not is
     *                   <i>refused</i> at run time — so a console that offers
     *                   retries without knowing this offers a scenario that will
     *                   not start.
     */
    public record Method(String name, boolean idempotent) {}

    /**
     * Everything in one system that a scenario could point at.
     *
     * @param jobs     classes implementing {@link Job}. A scenario names exactly one
     * @param services classes a machine can be given
     * @param other    how many other classes there are, so a student can tell the
     *                 difference between "nothing here is a service" and "nothing
     *                 here compiled"
     */
    public record Offer(List<String> jobs, List<Service> services, int other) {}

    private Palette() {}

    /**
     * Read a compiled system.
     *
     * @param classes where {@link Lab#compile} put them
     * @param lab     the lab, for the classpath the classes were compiled against
     * @param system  the system, only so a service can be pointed back at its source
     */
    public static Offer of(Path classes, Lab lab, Lab.System system) throws IOException {
        List<String> jobs = new ArrayList<>();
        List<Service> services = new ArrayList<>();
        int other = 0;

        List<URL> urls = new ArrayList<>();
        urls.add(classes.toUri().toURL());
        for (String part : lab.cp().split(File.pathSeparator)) {
            if (!part.isBlank()) urls.add(Path.of(part).toUri().toURL());
        }
        // The parent is losim's own loader, so `losim.api.Job` and `io.grpc` are
        // the same classes here as they are in the run — an `isAssignableFrom`
        // against a second copy of an interface is always false, and the symptom
        // would be a system whose services all vanished from the list.
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), Palette.class.getClassLoader())) {
            Class<?> bindable;
            try {
                bindable = Class.forName("io.grpc.BindableService", false, loader);
            } catch (ClassNotFoundException e) {
                // No gRPC on the lab's classpath at all. Nothing here can be a
                // service, and saying that is better than saying nothing.
                return new Offer(List.of(), List.of(), 0);
            }
            for (String name : names(classes)) {
                Class<?> type;
                try {
                    type = Class.forName(name, false, loader);
                } catch (Throwable e) {
                    // A class that will not even load is a build the student is in
                    // the middle of. It is not in the list; it is not an error page.
                    continue;
                }
                if (type.isInterface() || type.isEnum() || type.isAnnotation()
                        || Modifier.isAbstract(type.getModifiers())) {
                    continue;
                }
                if (Job.class.isAssignableFrom(type)) { jobs.add(name); continue; }
                if (!bindable.isAssignableFrom(type)) { other++; continue; }
                Object d = describe(type);
                if (d == null) { other++; continue; }
                String full = str(d, "getName");
                if (full == null) { other++; continue; }
                String bare = full.substring(full.lastIndexOf('.') + 1);
                List<Method> methods = new ArrayList<>();
                for (Object m : each(d, "getMethods")) {
                    String mm = str(m, "getFullMethodName");
                    if (mm == null) continue;
                    methods.add(new Method(mm.substring(mm.lastIndexOf('/') + 1),
                                           Boolean.TRUE.equals(flag(m, "isIdempotent"))));
                }
                methods.sort(Comparator.comparing(Method::name));
                services.add(new Service(name, bare, full, methods, sourceOf(lab, system, name)));
            }
        }
        jobs.sort(Comparator.naturalOrder());
        services.sort(Comparator.comparing(Service::cls));
        return new Offer(List.copyOf(jobs), List.copyOf(services), other);
    }

    /**
     * What a service offers, from the descriptor rather than from an instance.
     *
     * <p>Walks up to the generated {@code XImplBase}, whose enclosing {@code XGrpc}
     * has a static {@code getServiceDescriptor()}. That is grpc-java's own codegen
     * contract, and it is the only thing here that knows anything about generated
     * code — the alternative is {@code new Shrinker().bindService()}, which is a
     * student's constructor running inside the server that lists it.
     */
    private static Object describe(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Class<?> outer = c.getEnclosingClass();
            if (outer == null || !c.getSimpleName().endsWith("ImplBase")) continue;
            try {
                java.lang.reflect.Method m = outer.getDeclaredMethod("getServiceDescriptor");
                if (!Modifier.isStatic(m.getModifiers())) continue;
                m.setAccessible(true);
                return m.invoke(null);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Not the shape protoc generates. Whatever this is, it is not one
                // of ours, and guessing at it would put a wrong name on a machine.
            }
        }
        return null;
    }

    /** A no-argument getter that returns a string, on an object of a class we cannot name. */
    private static String str(Object on, String getter) {
        try {
            java.lang.reflect.Method m = on.getClass().getMethod(getter);
            m.setAccessible(true);
            Object v = m.invoke(on);
            return v == null ? null : String.valueOf(v);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The same, for one that returns a boolean. */
    private static Boolean flag(Object on, String getter) {
        try {
            java.lang.reflect.Method m = on.getClass().getMethod(getter);
            m.setAccessible(true);
            Object v = m.invoke(on);
            return v instanceof Boolean b ? b : Boolean.FALSE;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Boolean.FALSE;
        }
    }

    /** The same, for one that returns a collection. */
    private static Iterable<?> each(Object on, String getter) {
        try {
            java.lang.reflect.Method m = on.getClass().getMethod(getter);
            m.setAccessible(true);
            Object v = m.invoke(on);
            return v instanceof Iterable<?> it ? it : List.of();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }

    /** Every class in a compiled tree, by its fully qualified name. */
    private static List<String> names(Path classes) throws IOException {
        if (!Files.isDirectory(classes)) return List.of();
        try (Stream<Path> s = Files.walk(classes)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".class"))
                    .map(p -> classes.relativize(p).toString()
                            .replaceAll("\\.class$", "")
                            .replace(File.separatorChar, '.')
                            .replace('/', '.'))
                    // `Outer$1` is a lambda or an anonymous class: never something
                    // a scenario could name, and loading them is pure work.
                    .filter(n -> !n.matches(".*\\$\\d+.*"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * The file a class was written in, matched by its simple name.
     *
     * <p>Relative to the project, because this is shown to somebody who is looking
     * at that project in an editor — an absolute path from inside a container is
     * not a place they can go.
     */
    private static String sourceOf(Lab lab, Lab.System system, String cls) {
        String simple = cls.substring(cls.lastIndexOf('.') + 1);
        int nested = simple.indexOf('$');
        if (nested > 0) simple = simple.substring(0, nested);
        for (Path p : system.sources()) {
            if (!p.getFileName().toString().equals(simple + ".java")) continue;
            try {
                return lab.root().relativize(p.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
            } catch (IllegalArgumentException e) {
                return p.toString();   // somewhere outside the project entirely
            }
        }
        return null;
    }
}
