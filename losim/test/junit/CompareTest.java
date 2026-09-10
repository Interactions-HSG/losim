import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import dissaly.cli.Compare;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code dissaly compare}, on the two shapes {@code meta.scale} actually has.
 *
 * <p>It has two, and that is the whole reason this file exists. A direct run writes
 * the number it ran at; a scaled run writes the plan it was projected from. Reading
 * one as the other threw {@code Double cannot be cast to Map} on every direct trace
 * — which is to say on the commonest comparison a student makes, two ordinary
 * simulations of two designs.
 *
 * <p>It shipped because nothing here exercised it. The one job that would have,
 * {@code parity} in CI, {@code needs:} the Codespace suite, and that job had been
 * failing on an image with no Gradle since 2026-09-07 — so parity was skipped, not
 * red, across the whole restructuring of this CLI.
 */
class CompareTest {

    /** A direct run: `scale` is the number it ran at, and there is no plan. */
    private static String direct(String simulation, double seed) {
        return """
               { "schema": 4,
                 "meta": { "schemaVersion": 4, "simulation": "%s", "seed": %s,
                           "entry": "Main", "scale": 1, "unit": "frames", "count": 100,
                           "completed": true, "durationRefMs": 120 },
                 "events": [], "spans": [], "series": {}, "nodes": [] }
               """.formatted(simulation, seed);
    }

    /**
     * A scaled run: `scale` is the plan, and `laws` inside it are fitted.
     *
     * <p>`fixed` is what the fit measured, so the two arguments here are what one
     * simulation looks like run on two different hosts.
     */
    private static String scaled(double factor, double fittedFixed) {
        return """
               { "schema": 4,
                 "meta": { "schemaVersion": 4, "simulation": "thumbs.yaml", "seed": 5,
                           "entry": "Main", "unit": "frames", "count": 100,
                           "completed": true, "durationRefMs": 900,
                           "scale": { "units": 8000, "fullUnits": 48000, "factor": %s,
                                      "gridRuns": 28,
                                      "laws": { "allocMb": { "variable": "units",
                                                             "fixed": %s, "beta": 0.8 } },
                                      "refused": {} } },
                 "events": [], "spans": [], "series": {}, "nodes": [] }
               """.formatted(factor, fittedFixed);
    }

    private static Path write(Path dir, String name, String json) throws Exception {
        return Files.writeString(dir.resolve(name), json);
    }

    @Test
    @DisplayName("two ordinary simulations compare, rather than throwing on the scale they did not have")
    void directTracesCompare(@TempDir Path dir) throws Exception {
        int rc = Compare.of(write(dir, "a.json", direct("thumbs.yaml", 5)),
                            write(dir, "b.json", direct("thumbs.yaml", 5)));
        assertEquals(0, rc, "two runs of one simulation are the same simulator");
    }

    @Test
    @DisplayName("a fitted law differing between two hosts is host variation, not a defect")
    void fittedLawsAreMeasurements() throws Exception {
        Path dir = Files.createTempDirectory("cmp");
        int rc = Compare.of(write(dir, "a.json", scaled(6, 10.87)),
                            write(dir, "b.json", scaled(6, 11.42)));
        assertEquals(0, rc,
                "`fixed` is what the fit measured on this host. Held structurally it would"
                + " report two simulators every time two machines differed, which is the one"
                + " thing this comparison exists to tolerate.");
    }

    // Run backwards, twice: a comparison that cannot fail has proven nothing about
    // the two above, and both of those were made to pass by loosening something.

    @Test
    @DisplayName("and the scale a run was projected at still has to agree")
    void theFactorIsStructural() throws Exception {
        Path dir = Files.createTempDirectory("cmp");
        int rc = Compare.of(write(dir, "a.json", scaled(6, 10.87)),
                            write(dir, "b.json", scaled(12, 10.87)));
        assertEquals(1, rc, "x6 and x12 are not the same run, whatever the fits say");
    }

    @Test
    @DisplayName("and a direct run is not a scaled one")
    void directIsNotScaled() throws Exception {
        Path dir = Files.createTempDirectory("cmp");
        int rc = Compare.of(write(dir, "a.json", direct("thumbs.yaml", 5)),
                            write(dir, "b.json", scaled(6, 10.87)));
        assertEquals(1, rc, "one run at scale 1 against a projection from 28 is a difference");
    }
}
