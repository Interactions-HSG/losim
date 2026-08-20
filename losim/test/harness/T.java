package harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A very small test harness: enough to say what broke, and nothing more. */
public final class T {
    private static final List<String> failures = new ArrayList<>();
    private static int passed;
    private static String current = "";

    public static void suite(String name) { System.out.println("\n" + name); }

    public static void test(String name, Runnable body) {
        current = name;
        try {
            body.run();
            passed++;
            System.out.println("  ok   " + name);
        } catch (Throwable e) {
            failures.add(name + ": " + message(e));
            System.out.println("  FAIL " + name + " — " + message(e));
        }
    }

    static String message(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isBlank() ? e.getClass().getSimpleName() : m);
    }

    public static void eq(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    public static void isTrue(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    public static void contains(String haystack, String needle, String what) {
        if (haystack == null || !haystack.contains(needle))
            throw new AssertionError(what + ": expected to contain <" + needle + "> in <" + haystack + ">");
    }

    public static <E extends Throwable> E throwsA(Class<E> type, Runnable body, String what) {
        try {
            body.run();
        } catch (Throwable e) {
            if (type.isInstance(e)) return type.cast(e);
            throw new AssertionError(what + ": expected " + type.getSimpleName() + " but got " + e);
        }
        throw new AssertionError(what + ": expected " + type.getSimpleName() + ", nothing was thrown");
    }

    public static int exit() {
        System.out.println("\n" + passed + " passed, " + failures.size() + " failed");
        for (String f : failures) System.out.println("  FAIL " + f);
        return failures.isEmpty() ? 0 : 1;
    }
}
