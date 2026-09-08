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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import losim.sim.JavaSource;

/**
 * What the assignment's code offers a node.
 *
 * <p>A simulation places <b>files</b> on nodes, each under the service it
 * implements. Without this, the only way to know which files could be placed
 * would be to read the source, get the service name right by hand, and find out
 * at run time whether it was a service at all — and the error for getting it
 * wrong would arrive after a build, a generate and a JVM start. This reads the
 * answer off the compiled classes instead, so a console can offer the list
 * rather than asking somebody to remember it.
 *
 * <p><b>A path and a name, and they are different on purpose.</b> A node runs a
 * file ({@code src/Shrinker.java}) and thereby serves the gRPC service the class
 * in it implements ({@code losim.t.Thumbnailer}). One {@code runs:} entry is the
 * pair: peers find the node by the name, {@code simulatedDuration:} prices the
 * path. Both are reported here, together, so nobody has to hold the pair in
 * their head.
 *
 * <p><b>Only what can actually be written down.</b> A {@code runs:} value is a
 * path, and the class it reaches is the one its file is named after — so a
 * service nested inside another class, or compiled from source this project does
 * not hold, is counted rather than offered. Offering it would be offering a line
 * the loader refuses.
 *
 * <p><b>Nothing of the student's is executed.</b> Classes are loaded without
 * initialising them and are never constructed: what a service offers is read off
 * the descriptor {@code protoc} generated, which is losim's own dependency rather
 * than anybody's submission. A console that had to instantiate a class to list it
 * would run a constructor — and a constructor that loops is a server that stops
 * answering.
 *
 * <p><b>gRPC is reached by name, never by import.</b> The server is started with
 * {@code losim.jar} alone; the gRPC jars belong to the assignment and are only
 * ever on a simulation's classpath, in the JVM it forks. So every grpc type here
 * comes through the assignment's own loader and is used reflectively — importing
 * one would link this class against something the process listing it does not
 * have, and the symptom is a {@code NoClassDefFoundError} the moment somebody
 * opens the page.
 */
public final class Palette {

    /**
     * The service losim itself calls, and the only name in here that is losim's.
     *
     * <p>A literal rather than {@code JobGrpc.SERVICE_NAME}: reading that field
     * would import gRPC, which is the one thing this class cannot do.
     */
    private static final String JOB = "losim.Job";

    /**
     * One file a node can run.
     *
     * @param file    where it is, relative to the project — what a {@code runs:}
     *                entry takes as its value
     * @param service the gRPC service it implements, as gRPC names it on the wire —
     *                what a {@code runs:} entry takes as its key, and what
     *                {@code peersServing} finds the node by
     * @param rpcs    what can be called on it, and what {@code simulatedDuration:},
     *                {@code failures:} and {@code retries:} name
     * @param entry   whether this is {@code losim.Job}. Exactly one node runs one,
     *                and that call is where the simulation starts.
     */
    public record Service(String file, String service, List<Rpc> rpcs, boolean entry) {

        /** The service without its proto package, for a form that has room for one word. */
        public String bare() { return service.substring(service.lastIndexOf('.') + 1); }
    }

    /**
     * One rpc.
     *
     * @param idempotent whether the {@code .proto} declared it safe to run twice.
     *                   Carried because a retry policy on an rpc that did not is
     *                   <i>refused</i> at run time — so a console that offers
     *                   retries without knowing this offers a simulation that will
     *                   not start.
     */
    public record Rpc(String name, boolean idempotent) {}

    /**
     * Everything in the assignment a simulation could point at.
     *
     * <p>One list. What starts the work is a service like any other — the one
     * called {@code losim.Job} — so there is no second list to be in, and nothing
     * here has to construct a student's class to find out what it is. That used to
     * be the one place a form built one, to ask a driver object what its input was
     * made of; the input is three lines of YAML now and the form draws all three
     * without asking anybody.
     *
     * @param services files a node can be given
     * @param other    how many other classes there are, so a student can tell the
     *                 difference between "nothing here is a service" and "nothing
     *                 here compiled"
     */
    public record Offer(List<Service> services, int other) {}

    private Palette() {}

    /**
     * Read the assignment's compiled classes.
     *
     * @param classes where {@link Lab#compile} put them
     * @param lab     the assignment, for the classpath the classes were compiled against
     * @param sources its sources, which are what a simulation actually names
     */
    public static Offer of(Path classes, Lab lab, List<Path> sources) throws IOException {
        Map<String, String> files = files(lab, sources);
        List<Service> services = new ArrayList<>();
        int other = 0;

        List<URL> urls = new ArrayList<>();
        urls.add(classes.toUri().toURL());
        for (String part : lab.cp().split(File.pathSeparator)) {
            if (!part.isBlank()) urls.add(Path.of(part).toUri().toURL());
        }
        // The parent is losim's own loader, so `io.grpc.BindableService` is the
        // same class here as it is in the run — an `isAssignableFrom` against a
        // second copy of an interface is always false, and the symptom would be an
        // assignment whose services all vanished from the list.
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), Palette.class.getClassLoader())) {
            Class<?> bindable;
            try {
                bindable = Class.forName("io.grpc.BindableService", false, loader);
            } catch (ClassNotFoundException e) {
                // No gRPC on the assignment's classpath at all. Nothing here can be
                // a service, and saying that is better than saying nothing.
                return new Offer(List.of(), 0);
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
                if (!bindable.isAssignableFrom(type)) { other++; continue; }
                Object d = describe(type);
                String full = d == null ? null : str(d, "getName");
                // No path reaches it: nested inside another class, or compiled from
                // source that is not in this project. It is a service and it cannot
                // be placed, which is the same thing as far as a form is concerned.
                String file = files.get(name);
                if (full == null || file == null) { other++; continue; }
                List<Rpc> rpcs = new ArrayList<>();
                for (Object m : each(d, "getMethods")) {
                    String mm = str(m, "getFullMethodName");
                    if (mm == null) continue;
                    rpcs.add(new Rpc(mm.substring(mm.lastIndexOf('/') + 1),
                                     Boolean.TRUE.equals(flag(m, "isIdempotent"))));
                }
                rpcs.sort(Comparator.comparing(Rpc::name));
                services.add(new Service(file, full, List.copyOf(rpcs), full.equals(JOB)));
            }
        }
        services.sort(Comparator.comparing(Service::file));
        return new Offer(List.copyOf(services), other);
    }

    /**
     * Every class this project has a path to, by the name it will be loaded under.
     *
     * <p>The same two questions the loader asks of a {@code runs:} value, asked
     * with the same reader: does the file declare a type named after itself, and
     * what is that type's qualified name. Matching on the simple name alone would
     * hand the console {@code src/Shrinker.java} for a {@code lab.two.Shrinker}
     * when the file holds {@code lab.one.Shrinker} — a line that looks right and
     * loads the wrong class.
     */
    private static Map<String, String> files(Lab lab, List<Path> sources) {
        var out = new LinkedHashMap<String, String>();
        for (Path p : sources) {
            try {
                if (!JavaSource.declaresItsOwnName(p)) continue;
                out.putIfAbsent(JavaSource.className(p), rel(lab, p));
            } catch (IOException ignored) { /* unreadable source is javac's to report */ }
        }
        return out;
    }

    /**
     * A path relative to the project, because this is shown to somebody looking at
     * that project in an editor — an absolute path from inside a container is not a
     * place they can go, and it is not a value {@code runs:} takes either.
     */
    private static String rel(Lab lab, Path p) {
        try {
            return lab.root().relativize(p.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return p.toString();       // somewhere outside the project entirely
        }
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
                // of ours, and guessing at it would put a wrong name on a node.
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
                    // a simulation could name, and loading them is pure work.
                    .filter(n -> !n.matches(".*\\$\\d+.*"))
                    .sorted()
                    .toList();
        }
    }
}
