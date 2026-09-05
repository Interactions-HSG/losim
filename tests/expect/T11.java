import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * t11-scale-wordcount — the same pipeline, scaled, across a matrix.
 *
 * <p><b>Catches:</b> the engine folding the fleet dimension into the data dimension.
 * That is the failure mode that makes every projection plausible and wrong, because
 * a resource driven by the data and a resource driven by the fleet look identical if
 * the two only ever move together. Nothing looks broken; the numbers are simply
 * about the wrong thing.
 *
 * <p>Five cells, and each one climbs a four-rung data ladder of its own, so the data
 * axis is inside every cell rather than beside them:
 *
 * <pre>
 *   fleet:    2 workers   4 workers   8 workers      (clean)
 *   weather:  clean       one kill    standing chaos (4 workers)
 * </pre>
 *
 * <p>What has to hold across the fleet row is that each resource keeps the same
 * <i>independent variable</i> and the same exponent, while its <i>coefficient</i>
 * moves in the way that shape demands — memory per distinct key is the same whoever
 * holds the key, and disk per machine halves when there are twice as many machines
 * to spread the volume over. An engine that had folded the two axes together would
 * get one of those two wrong.
 */
public final class T11 {

    record Cell(String name, Expect e) {
        Map<String, Object> law(String resource) {
            return T10.sub(T10.sub(T10.sub(e.meta(), "scale"), "laws"), resource);
        }
        boolean projected(String resource) { return !law(resource).isEmpty(); }
        double beta(String r)        { return Expect.num(law(r).get("beta")); }
        double coefficient(String r) { return Expect.num(law(r).get("coefficient")); }
        String of(String r)          { return String.valueOf(law(r).get("variable")); }
        double phase(String label) {
            return e.spansOf("phase").stream().filter(s -> label.equals(s.get("label")))
                    .mapToDouble(s -> Expect.num(s.get("t1")) - Expect.num(s.get("t0")))
                    .max().orElse(-1);
        }

        /**
         * How long the busiest machine spent inside one method's handlers.
         *
         * <p>Summed from the spans rather than read off the wall clock, because a
         * declared duration is slept and a sleeping thread needs no core. This
         * number is therefore the same on a laptop and on a two-core runner, where
         * the phase's wall clock is not: that one carries the coordinator's own
         * serial work, which does scale with how fast the host is.
         */
        double busiestIn(String method) {
            var perMachine = new java.util.HashMap<String, Double>();
            for (var s : e.spansOf("handler")) {
                if (!String.valueOf(s.get("label")).endsWith(method)) continue;
                perMachine.merge(String.valueOf(s.get("vm")),
                        Expect.num(s.get("t1")) - Expect.num(s.get("t0")), Double::sum);
            }
            return perMachine.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        }
        @SuppressWarnings("unchecked")
        List<String> notes() {
            var out = new ArrayList<String>();
            for (Object n : (List<Object>) T10.sub(e.meta(), "scale")
                    .getOrDefault("notes", List.of())) out.add(String.valueOf(n));
            return out;
        }
    }

    public static void main(String[] args) {
        var e = Expect.of("t11-scale-wordcount", args);
        var cells = new LinkedHashMap<String, Cell>();
        String[] names = {"fleet2", "fleet4", "fleet8", "kill", "chaos"};
        for (int i = 0; i < names.length; i++)
            cells.put(names[i], new Cell(names[i],
                    i == 0 ? e : Expect.of("", new String[]{args[i]})));

        var fleet = List.of(cells.get("fleet2"), cells.get("fleet4"), cells.get("fleet8"));

        // Every resource keeps the variable it is a function of, whatever the fleet.
        // The one that must never move is memory: it is a function of distinct keys,
        // and "how many machines are there" is not an account of how many words there
        // were.
        int wrongVariable = 0;
        for (Cell c : fleet) {
            if (!"revealed.distinctKeys".equals(c.of("memoryMb"))) wrongVariable++;
            for (String r : List.of("wireMb", "diskMb"))
                if (c.projected(r) && !"records".equals(c.of(r))) wrongVariable++;
        }
        e.check(wrongVariable == 0,
                "at 2, 4 and 8 workers, memory is a function of distinct keys and wire and disk "
                + "are functions of records — the attribution does not move when the fleet does, "
                + "and the two never swap");

        double lo = fleet.stream().mapToDouble(c -> c.beta("memoryMb")).min().orElse(0);
        double hi = fleet.stream().mapToDouble(c -> c.beta("memoryMb")).max().orElse(0);
        e.note(String.format("memory exponent across the fleet row: %s",
                fleet.stream().map(c -> String.format("%.3f", c.beta("memoryMb"))).toList()));
        e.check(hi - lo < 0.05, String.format(
                "and the memory exponent moves by %.3f across the whole row — a key costs what "
                + "a key costs, and it does not become cheaper because there are more machines "
                + "to hold one", hi - lo));

        // The other half of the same claim, and the one that proves the fleet axis
        // was actually seen rather than ignored. Volume is split across machines, so
        // twice the fleet is half the disk each.
        double disk2 = cells.get("fleet2").coefficient("diskMb");
        double disk4 = cells.get("fleet4").coefficient("diskMb");
        double ratio = disk4 > 0 ? disk2 / disk4 : 0;
        e.note(String.format("disk per machine: %.3g at 2 workers, %.3g at 4 (a factor of %.2f)",
                disk2, disk4, ratio));
        e.check(ratio > 1.6 && ratio < 2.5,
                "while what each machine writes to disk halves when the fleet doubles — the "
                + "fleet axis was varied independently of the data axis, so a resource that "
                + "follows the fleet is told apart from one that follows the data");

        // Observed, not projected: the timeline law is refused on handlers this short
        // (D7), and asserting a projected makespan here would be asserting a number
        // the engine declines to produce.
        double map2 = cells.get("fleet2").busiestIn("Map"), map8 = cells.get("fleet8").busiestIn("Map");
        double col2 = cells.get("fleet2").phase("collect"), col8 = cells.get("fleet8").phase("collect");
        e.note(String.format("the busiest machine spends %.0f refMs mapping at 2 workers and "
                + "%.0f at 8; the collect phase runs %.0f refMs and then %.0f", map2, map8, col2, col8));
        e.note(String.format("(the map phase's own wall clock: %.0f then %.0f — shorter, but by "
                + "less, because the coordinator's serial share of it is not the fleet's to divide)",
                cells.get("fleet2").phase("map"), cells.get("fleet8").phase("map")));
        // Asserted as one speedup divided by the other, because on a slow host
        // neither number means anything on its own.
        //
        // Three shapes have been tried here and the first two were wrong in the
        // same way. It began as a floor under the merge phase's own duration —
        // `col8 > col2 * 0.9`, the merge is never shorter — which failed about one
        // run in three, not because the merge ever parallelised but because col2
        // is a ~200 refMs phase measured in wall clock that includes what losim
        // itself costs. Across nine runs it sat between 185 and 219 eight times
        // and came back 478 once, and that once the run "failed" for having a
        // merge too *slow* at two workers.
        //
        // The second shape was two absolute bounds, map above 3 and merge below 2,
        // on the strength of a gap that looked empty on a twelve-core laptop: map
        // 3.77 to 4.23, merge 0.54 to 1.49. CI showed the gap was an artefact of
        // measuring one machine. On two-core runners the map speedup has come back
        // at 3.10, 2.49 and then 1.82 — below what had been called the floor, and
        // near the merge maximum. Eight workers sleep their declared costs
        // concurrently on any host, but the protobuf, the gRPC and the trace
        // around those sleeps are real work, and where the host has two cores it
        // is the host that is the bottleneck rather than the design.
        //
        // So neither figure is a fact about scaling on its own. Their ratio is:
        // a slow host drags both phases down together, and dividing one by the
        // other takes the host out. Over 22 runs on both hardware classes it
        // stays between 2.74 and 8.86, where map alone spans 1.82 to 4.31 and
        // merge 0.35 to 1.49. The bound is 2, with 37% of margin under the
        // worst run seen — which is the one whose col2 was inflated to 478, so
        // the two failure modes are covered by the same number.
        double mapSpeedup = map8 > 0 ? map2 / map8 : 0;
        double colSpeedup = col8 > 0 ? col2 / col8 : 0;
        double apart = colSpeedup > 0 ? mapSpeedup / colSpeedup : 0;
        e.note(String.format("mapping is %.2fx faster on four times the fleet and merging %.2fx"
                + " — a factor of %.2f between them", mapSpeedup, colSpeedup, apart));
        e.check(apart > 2,
                "and four times the fleet divides the work of fanning out while leaving the "
                + "phase that merges no faster — which is the difference a projection has to "
                + "keep, because it is the difference between a design that scales and one "
                + "that does not");


        // The fault dimension. A model fitted only on clean runs under-predicts a
        // fleet that loses a machine, and it under-predicts optimistically.
        var clean = cells.get("fleet4");
        double amplified = Expect.num(cells.get("kill").law("memoryMb").get("faultAmplification"));
        e.check(!clean.law("memoryMb").containsKey("faultAmplification") && amplified > 1.01,
                String.format("killing a worker raises the memory the model expects by x%.2f, "
                        + "where the clean cell carries no such term at all — the dead machine's "
                        + "chunks are done again somewhere, and some survivor absorbs its bucket",
                        amplified));

        String warns = "declares no faults";
        boolean cleanSaysSo = clean.notes().stream().anyMatch(n -> n.contains(warns));
        // `noneMatch` over an empty list is true, and this deliberately does not
        // guard against that. It was guarded for one run, on the general principle
        // that a check passing over nothing has proved nothing, and the guard was
        // wrong here: a weathered cell that emits no notes at all is a cell that
        // does not warn, which is exactly the claim. Requiring notes to exist
        // asserted something the engine never promised, and duly failed.
        //
        // What makes the vacuous case unreachable is the line above. If notes had
        // stopped working altogether, `cleanSaysSo` is an anyMatch and fails
        // first — so the pair cannot both pass on an empty world.
        boolean weatheredDoesNot = cells.get("kill").notes().stream().noneMatch(n -> n.contains(warns))
                && cells.get("chaos").notes().stream().noneMatch(n -> n.contains(warns));
        e.check(cleanSaysSo && weatheredDoesNot,
                "and the clean cell says out loud that its model describes a fleet where nothing "
                + "goes wrong, while the two weathered cells do not — an absent fault column is a "
                + "limit of the model, not an absence of one");
        e.done();
    }
}
