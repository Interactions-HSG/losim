package losim.scale;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import losim.scenario.Scenario;
import losim.trace.Json;
import losim.trace.JsonReader;

/**
 * The plan, kept between runs.
 *
 * <p>Fitting a plan costs a hundred small runs. That is affordable once and absurd
 * every time, so it is cached against the two things that can invalidate it: the
 * scenario, and the code it profiles. Change either and the plan is refitted;
 * change neither and a scaled run starts immediately.
 *
 * <p>Cached under {@code build/}, deliberately — a plan is derived, not authored,
 * and a stale one committed to a repository would be worse than no cache at all.
 */
public final class Plans {
    private Plans() {}

    private static final Path CACHE = Path.of("build", ".losim-plans");

    /**
     * What this plan was fitted from.
     *
     * <p>The scenario as loaded — so a comment or a reformat does not invalidate it,
     * but a changed fault does — and every class the job might use, by content.
     */
    public static String key(Scenario s, ClassLoader loader, List<Path> code) {
        var sb = new StringBuilder();
        sb.append(s.job()).append('|').append(s.records()).append('|').append(s.seed())
          .append('|').append(s.kTime()).append('|').append(s.expectedRunRefMs());
        for (var m : s.machines())
            sb.append('|').append(m.name()).append(':').append(m.instance())
              .append(':').append(m.zone()).append(':').append(m.serves());
        sb.append('|').append(s.net()).append('|').append(s.faults()).append('|').append(s.chaos())
          .append('|').append(s.retries());
        if (s.workload() != null)
            sb.append('|').append(s.workload().probeSizes())
              .append('|').append(s.workload().fleetSizes());
        for (Path p : code) sb.append('|').append(fingerprint(p));
        return sha(sb.toString());
    }

    /** Every class file under a path, by size and modification time. */
    private static String fingerprint(Path root) {
        if (!Files.exists(root)) return root + ":absent";
        var sb = new StringBuilder();
        try (var walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".class")).sorted().forEach(p -> {
                try {
                    sb.append(p).append(':').append(Files.size(p)).append(':')
                      .append(Files.getLastModifiedTime(p).toMillis()).append(';');
                } catch (IOException e) { sb.append(p).append(":?;"); }
            });
        } catch (IOException e) {
            sb.append(root).append(":unreadable");
        }
        return sb.toString();
    }

    private static String sha(String s) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes());
            var sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    public static Optional<ScalePlan> load(String key) {
        Path file = CACHE.resolve(key + ".json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(fromMap(JsonReader.readObject(Files.readString(file))));
        } catch (Exception e) {
            return Optional.empty();          // an unreadable cache is a cache miss, not a failure
        }
    }

    public static void save(String key, ScalePlan plan) {
        try {
            Files.createDirectories(CACHE);
            Files.writeString(CACHE.resolve(key + ".json"), Json.write(plan.asMap()));
        } catch (IOException ignored) {
            // Not being able to cache a plan costs time, not correctness.
        }
    }

    @SuppressWarnings("unchecked")
    static ScalePlan fromMap(Map<String, Object> m) {
        long records = ((Number) m.get("records")).longValue();
        long full = ((Number) m.get("fullRecords")).longValue();
        double kTime = ((Number) m.get("kTime")).doubleValue();
        int runs = m.containsKey("gridRuns") ? ((Number) m.get("gridRuns")).intValue() : 0;

        var byResource = new TreeMap<String, Fit.Law>();
        var errorBars = new TreeMap<String, Double>();
        var amplification = new TreeMap<String, Double>();
        var laws = (Map<String, Object>) m.getOrDefault("laws", Map.of());
        for (var e : laws.entrySet()) {
            var l = (Map<String, Object>) e.getValue();
            byResource.put(e.getKey(), new Fit.Law(e.getKey(),
                    (String) l.get("variable"), num(l, "fixed"), num(l, "coefficient"),
                    num(l, "beta"), num(l, "r2"), num(l, "wobble")));
            errorBars.put(e.getKey(), l.containsKey("errorBar") ? num(l, "errorBar") : 1.0);
            if (l.containsKey("faultAmplification"))
                amplification.put(e.getKey(), num(l, "faultAmplification"));
        }
        var refused = new TreeMap<String, String>();
        ((Map<String, Object>) m.getOrDefault("refused", Map.of()))
                .forEach((k, v) -> refused.put(k, String.valueOf(v)));
        var byVariable = new TreeMap<String, Fit.Law>();
        ((Map<String, Object>) m.getOrDefault("variables", Map.of())).forEach((k, v) -> {
            var l = (Map<String, Object>) v;
            byVariable.put(k, new Fit.Law(k, "records", num(l, "fixed"), num(l, "coefficient"),
                    num(l, "beta"), num(l, "r2"), num(l, "wobble")));
        });

        var caps = new LinkedHashMap<String, double[]>();
        ((Map<String, Object>) m.getOrDefault("caps", Map.of())).forEach((k, v) -> {
            var pair = (List<Object>) v;
            caps.put(k, new double[]{((Number) pair.get(0)).doubleValue(),
                                     ((Number) pair.get(1)).doubleValue()});
        });
        var notes = new ArrayList<String>();
        for (Object n : (List<Object>) m.getOrDefault("notes", List.of())) notes.add(String.valueOf(n));

        return new ScalePlan(records, full, kTime, caps,
                new Laws(byResource, errorBars, refused, new TreeMap<>(), amplification, byVariable),
                runs, notes, (String) m.get("infeasible"));
    }

    private static double num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : 0;
    }
}
