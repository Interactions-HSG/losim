import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import dissaly.sim.Loader;
import dissaly.sim.Yaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every simulation this repository carries still loads.
 *
 * <p>A simulation is data rather than code, so nothing compiles it and nothing
 * reads it until somebody opens the one that broke — and a catalogue edit, such
 * as deleting an instance family, is exactly the kind of change that can orphan
 * many simulations at once, without touching a single simulation file, while every
 * other check suite stays green.
 *
 * <p>Loading only, not running: running them is minutes of cluster time and
 * belongs to whoever regenerates the traces. What this protects is the part that
 * rots silently — that every instance type, zone, fault target and duration
 * named in them still means something.
 */
class GalleryTest {

    /**
     * Where simulations live.
     *
     * <p>The gallery is one of them. It is a tracked directory like the other
     * two: worked examples are reviewed like anything else this repository
     * ships, and a catalogue edit that orphaned one would otherwise be found by
     * whoever opened it next rather than here.
     *
     * <p>{@code demo/} is in {@code .gitignore}, so it is a bonus rather than a
     * requirement: a project somebody has on this machine and nobody has in a
     * fresh clone, and this test must pass in both. The tracked directories are
     * the ones that must never be empty — if they are, the simulations moved and
     * this test would otherwise sit here passing on nothing at all.
     */
    private static final List<String> TRACKED = List.of(
            "tests/simulations", "tests/gallery/simulations", "losim/test/simulations");
    private static final String UNTRACKED = "demo";

    private static List<Path> yamlUnder(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return List.of();
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .sorted().toList();
        }
    }

    @Test
    @DisplayName("every simulation in the repository loads, so a catalogue change cannot quietly orphan one")
    void everyScenarioLoads() throws Exception {
        var files = new ArrayList<Path>();
        for (String dir : TRACKED) {
            var found = yamlUnder(Path.of(dir));
            assertFalse(found.isEmpty(), "no simulations under " + dir + " — have they moved?");
            files.addAll(found);
        }
        // Whatever the gallery holds, if this machine has one.
        files.addAll(yamlUnder(Path.of(UNTRACKED)));

        var broken = new ArrayList<String>();
        for (Path f : files) {
            try {
                Loader.of(Yaml.parse(f.getFileName().toString(), Files.readString(f)));
            } catch (RuntimeException e) {
                broken.add(f + "\n      " + e.getMessage());
            }
        }
        assertTrue(broken.isEmpty(),
                broken.size() + " of " + files.size() + " simulations no longer load:\n  "
                + String.join("\n  ", broken));
    }
}
