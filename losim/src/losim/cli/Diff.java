package losim.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import losim.trace.JsonReader;

/**
 * Comparing two traces of the same scenario, run in two places.
 *
 * <p>The requirement is that the same commands produce the same result in the
 * devcontainer, on a laptop and in a Codespace — otherwise a number depends on
 * where it was computed, which is the one thing a simulator cannot afford. But
 * runs are not reproducible even in one place (D1): real threads, a real clock, and
 * a deliberate refusal to simulate a scheduler. Two runs of one scenario on one
 * machine differ in every duration and most byte counts.
 *
 * <p>So a plain textual diff could never pass, and a check that could never pass is
 * not a check. What has to agree is <b>the structure and the attribution</b> — what
 * kinds of thing happened, what each carried, which machines existed, and what each
 * resource was found to be a function of. What is allowed to differ is <b>the
 * measurements</b>, and those are printed rather than judged: a Codespace is slower
 * than a laptop, and that is what host calibration is for.
 *
 * <p>The distinction is the whole content of this file. A structural difference is a
 * defect — the two environments are not running the same simulator. A measured
 * difference is information, and the size of it is worth seeing.
 */
public final class Diff {
    private Diff() {}

    /** One thing compared, and whether disagreeing about it is a defect. */
    private record Aspect(String what, boolean structural, Object left, Object right) {
        boolean agrees() { return Objects.equals(left, right); }
    }

    public static int run(Path a, Path b) throws Exception {
        var left = JsonReader.readObject(Files.readString(a));
        var right = JsonReader.readObject(Files.readString(b));
        var aspects = new ArrayList<Aspect>();

        aspects.add(new Aspect("schema", true, left.get("schema"), right.get("schema")));
        for (String key : List.of("scenario", "job", "seed", "kTime", "telemetry", "mode",
                                  "schemaVersion", "trusted", "completed"))
            if (meta(left).containsKey(key) || meta(right).containsKey(key))
                aspects.add(new Aspect("meta." + key, true, meta(left).get(key), meta(right).get(key)));

        // The D9 acceptance test, applied across environments rather than across
        // versions. Split in two, and the split is the point: what a kind of event
        // carries is a property of the recorder and must not vary, while whether a
        // kind occurred at all is a property of the afternoon. A queue_wait appears
        // when a call had to queue, and on a busier host more calls queue — that is
        // the simulator working, not two simulators disagreeing.
        aspects.addAll(shared("what each event kind carries", shape(left), shape(right)));
        aspects.addAll(shared("what each span kind is labelled", spanShape(left), spanShape(right)));
        aspects.add(new Aspect("sampled channels", true, channels(left), channels(right)));
        aspects.add(new Aspect("machines", true, machineNames(left), machineNames(right)));

        // Attribution is structural; the fitted numbers are not. Which variable a
        // resource turned out to be a function of is a fact about the program, and it
        // must not depend on where the program ran. The exponent is a measurement.
        aspects.add(new Aspect("what each resource is a function of", true,
                attribution(left), attribution(right)));
        aspects.add(new Aspect("what the engine refused", true, refused(left), refused(right)));

        for (String key : List.of("durationRefMs"))
            if (meta(left).containsKey(key))
                aspects.add(new Aspect("meta." + key, false, meta(left).get(key), meta(right).get(key)));
        aspects.add(new Aspect("run size chosen", false,
                scale(left).get("records"), scale(right).get("records")));
        for (var e : exponents(left).entrySet())
            aspects.add(new Aspect("exponent of " + e.getKey(), false,
                    e.getValue(), exponents(right).get(e.getKey())));

        int broken = 0;
        System.out.printf("%s%n%s%n%n", a, b);
        for (Aspect aspect : aspects) {
            if (aspect.agrees()) {
                System.out.printf("  same    %s%n", aspect.what());
            } else if (aspect.structural()) {
                broken++;
                System.out.printf("  DIFFER  %s%n            %s%n            %s%n",
                        aspect.what(), brief(aspect.left()), brief(aspect.right()));
            } else {
                System.out.printf("  host    %s: %s against %s%n",
                        aspect.what(), brief(aspect.left()), brief(aspect.right()));
            }
        }
        System.out.println();
        if (broken == 0) {
            System.out.println("  These two runs are the same simulator. What differs between them"
                    + " is what the\n  two hosts are, which is what host calibration exists to"
                    + " absorb.");
            return 0;
        }
        System.out.printf("  %d structural difference%s. These are not the same simulator, and a"
                + " number\n  computed in one of these places does not mean the same thing in the"
                + " other.%n", broken, broken == 1 ? "" : "s");
        return 1;
    }

    /**
     * Compared where both sides have it; reported where only one does.
     *
     * <p>A kind of thing that happened in one run and not the other is a difference
     * between two afternoons. A kind that happened in both and was recorded
     * differently is a difference between two simulators, and only the second is a
     * defect.
     */
    private static List<Aspect> shared(String what, Map<String, Set<String>> left,
                                       Map<String, Set<String>> right) {
        var out = new ArrayList<Aspect>();
        var both = new TreeSet<>(left.keySet());
        both.retainAll(right.keySet());
        var mine = new TreeMap<String, Set<String>>();
        var theirs = new TreeMap<String, Set<String>>();
        for (String kind : both) { mine.put(kind, left.get(kind)); theirs.put(kind, right.get(kind)); }
        out.add(new Aspect(what + " (" + both.size() + " in both)", true, mine, theirs));

        var onlyLeft = new TreeSet<>(left.keySet());
        onlyLeft.removeAll(right.keySet());
        var onlyRight = new TreeSet<>(right.keySet());
        onlyRight.removeAll(left.keySet());
        if (!onlyLeft.isEmpty() || !onlyRight.isEmpty())
            out.add(new Aspect(what.replace("what each", "which") + " occurred at all",
                    false, onlyLeft, onlyRight));
        return out;
    }

    // ----------------------------------------------------------------- reading

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(Map<String, Object> t) {
        return (Map<String, Object>) t.getOrDefault("meta", Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scale(Map<String, Object> t) {
        return (Map<String, Object>) meta(t).getOrDefault("scale", Map.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> t, String key) {
        return (List<Map<String, Object>>) t.getOrDefault(key, List.of());
    }

    /** Every event kind, with the union of the keys it carried. */
    private static Map<String, Set<String>> shape(Map<String, Object> t) {
        var out = new TreeMap<String, Set<String>>();
        for (var e : rows(t, "events")) {
            @SuppressWarnings("unchecked")
            var detail = (Map<String, Object>) e.getOrDefault("detail", Map.of());
            out.computeIfAbsent(String.valueOf(e.get("kind")), k -> new TreeSet<>())
               .addAll(detail.keySet());
        }
        return out;
    }

    private static Map<String, Set<String>> spanShape(Map<String, Object> t) {
        var out = new TreeMap<String, Set<String>>();
        for (var s : rows(t, "spans"))
            out.computeIfAbsent(String.valueOf(s.get("kind")), k -> new TreeSet<>())
               .add(String.valueOf(s.get("label")));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> channels(Map<String, Object> t) {
        var series = (Map<String, Object>) t.getOrDefault("series", Map.of());
        return new TreeSet<>(((Map<String, Object>) series.getOrDefault("channels", Map.of())).keySet());
    }

    private static List<String> machineNames(Map<String, Object> t) {
        return rows(t, "machines").stream().map(m -> String.valueOf(m.get("name"))).sorted().toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> attribution(Map<String, Object> t) {
        var out = new TreeMap<String, String>();
        ((Map<String, Object>) scale(t).getOrDefault("laws", Map.of())).forEach((resource, law) ->
                out.put(resource, String.valueOf(((Map<String, Object>) law).get("variable"))));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> refused(Map<String, Object> t) {
        return new TreeSet<>(((Map<String, Object>) scale(t).getOrDefault("refused", Map.of())).keySet());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> exponents(Map<String, Object> t) {
        var out = new TreeMap<String, Object>();
        ((Map<String, Object>) scale(t).getOrDefault("laws", Map.of())).forEach((resource, law) ->
                out.put(resource, ((Map<String, Object>) law).get("beta")));
        return out;
    }

    private static String brief(Object o) {
        String s = String.valueOf(o);
        return s.length() <= 160 ? s : s.substring(0, 157) + "...";
    }
}
