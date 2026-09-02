package losim.price;

import java.util.*;
import losim.res.InstanceCatalog;

/**
 * Turning a run into a bill, at both of its scales.
 *
 * <p>Generic over labs: it reads only the trace, so any gRPC system gets a cost view
 * without anyone writing one for it.
 *
 * <h2>Why there are two bills, and why the second one has holes in it</h2>
 *
 * A scaled run's numbers are a model of something bigger, so the interesting bill is
 * the one for the job that was actually being asked about. But a bill is quantities
 * times prices, and the engine will not project every quantity: on handlers shorter
 * than the host's own jitter it refuses the timeline outright (D6).
 *
 * <p>Capacity is the line that depends on the timeline, and capacity is usually the
 * largest line on the bill. So the projected account routinely comes out saying: the
 * bytes cost this much, the storage costs this much, <i>and nobody can tell you what
 * the machines cost, because nobody can tell you how long the job takes.</i> That is
 * the honest answer and it is a useful one — it says where the uncertainty in the
 * cost of a design actually lives, which is not where most people would guess.
 *
 * <p>Events are never projected (D7). A machine either failed or it did not, and an
 * incident bucket extrapolated from one afternoon would be a forecast dressed as an
 * observation.
 */
public final class Bill {
    private Bill() {}

    /** Both accounts, and the projected one is absent where the run was not scaled. */
    public record Both(PnL observed, PnL projected) {

        public Map<String, Object> asMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("observed", observed.asMap());
            if (projected != null) m.put("projected", projected.asMap());
            return m;
        }
    }

    public static Both of(Map<String, Object> trace, PriceList prices) {
        var meta = sub(trace, "meta");
        var machines = rows(trace, "machines");
        var events = rows(trace, "events");
        boolean scaled = "scaled".equals(meta.get("mode"));

        var observed = new PnL(prices.currency, "observed");
        price(observed, prices, meta, machines, events,
              num(meta.get("durationRefMs")), 1.0, null);
        if (!scaled) return new Both(observed, null);

        // The same accounting, over the projected quantities instead of the measured
        // ones. Each resource carries its own factor, because that is the whole point
        // of the engine: they do not share one.
        var projections = new LinkedHashMap<String, Map<String, Object>>();
        for (Object p : (List<?>) meta.getOrDefault("projections", List.of()))
            if (p instanceof Map<?, ?> row)
                projections.put(String.valueOf(row.get("resource")), cast(row));

        var projected = new PnL(prices.currency, "projected");
        price(projected, prices, meta, machines, events,
              projectedOrNaN(projections, "makespanRefMs"),
              growth(projections, "wireMb"), projections);
        return new Both(observed, projected);
    }

    /**
     * @param durationRefMs how long the job takes at this scale, or NaN if unknown
     * @param wireGrowth    how much more traffic there is at this scale than was seen,
     *                      or NaN where the engine refused to say
     * @param projections   the engine's own column, so a refusal can be quoted rather
     *                      than paraphrased. Null when nothing is being projected.
     */
    private static void price(PnL pnl, PriceList prices, Map<String, Object> meta,
                              List<Map<String, Object>> machines,
                              List<Map<String, Object>> events,
                              double durationRefMs, double wireGrowth,
                              Map<String, Map<String, Object>> projections) {

        // --- capacity: an idle machine costs exactly as much as a busy one.
        // Priced in reference time, deliberately. The run took a few seconds of
        // somebody's afternoon; the job it is a model of takes what the simulated
        // clock says, and that is the number anyone would be invoiced for.
        if (Double.isNaN(durationRefMs)) {
            pnl.cannotPrice("capacity", "the fleet, for the period",
                    projections == null ? "the run has no duration"
                            : refusal(projections, "makespanRefMs"));
        } else {
            double seconds = Math.max(prices.billingMinimumSeconds, durationRefMs / 1000.0);
            var spot = reclaimed(events);
            for (var m : machines) {
                String instance = String.valueOf(m.get("instance"));
                double onDemand = rate(instance);
                boolean onSpot = spot.contains(String.valueOf(m.get("name")));
                pnl.add("capacity", m.get("name") + " (" + instance
                                + (onSpot ? ", spot" : "") + ")",
                        seconds / 3600.0, "machine-hours", prices.perHour(onDemand, onSpot),
                        "reserved for the whole period, billed per second with a "
                                + prices.billingMinimumSeconds + "s minimum");
            }
        }

        // --- consumption: what the work actually burned.
        //
        // Cross-zone bytes are grown by the wire law rather than by one of their own.
        // Which calls cross a zone is a property of the topology and not of the size,
        // so the share is held fixed and the total is what moves — but if the wire law
        // itself was refused there is no factor to apply, and the observed quantity
        // printed under a full-scale heading would be a silently wrong number.
        double crossZoneMb = sum(machines, "crossZoneMb");
        if (Double.isNaN(wireGrowth) && crossZoneMb > 0)
            pnl.cannotPrice("consumption", "cross-zone egress", refusal(projections, "wireMb"));
        else if (crossZoneMb > 0)
            egress(pnl, machines, crossZoneMb, wireGrowth, prices);

        Double disk = quantity(projections, "diskMb", peak(machines, "diskMb"));
        if (disk == null)
            pnl.cannotPrice("consumption", "spilled data on disk",
                    refusal(projections, "diskMb"));
        else if (disk > 0)
            pnl.add("consumption", "spilled data on disk", disk / 1024.0, "GB-month",
                    prices.storagePerGbMonth,
                    "the worst machine's spill, held for the period");

        // --- incidents: zero until something breaks, then large. Observed only:
        // an OutOfMemory either happened or it did not, and neither is a forecast.
        long timeouts = count(events, "rpc_timeout");
        if (timeouts > 0)
            pnl.add("incidents", "calls that did not answer in time", timeouts, "reruns",
                    prices.incidentPerRerun,
                    "work redone because a machine did not answer inside its deadline");
        long lost = count(events, "kill") + count(events, "spot_notice");
        if (lost > 0)
            pnl.add("incidents", "machines lost", lost, "failures",
                    prices.incidentPerLostMachine,
                    "recovering from a machine that went away mid-job");
        long full = count(events, "oom") + count(events, "disk_full");
        if (full > 0)
            pnl.add("incidents", "machines that filled up", full, "failures",
                    prices.incidentPerLostMachine * 5,
                    "a machine sized too small for what its design asked it to hold");
        if (!Double.isNaN(durationRefMs)) {
            double late = Math.max(0, durationRefMs / 1000.0 - prices.slaSeconds);
            if (late > 0)
                pnl.add("incidents", "late finish", late, "seconds", prices.latePenaltyPerSecond,
                        "past the " + prices.slaSeconds + "s service level");
        }

        // --- build: carried whether or not it is ever needed.
        var services = new TreeSet<String>();
        for (var m : machines)
            for (Object s : (List<?>) m.getOrDefault("serves", List.of()))
                services.add(String.valueOf(s));
        int distinct = Math.max(1, services.size());
        pnl.add("build", "services carried", distinct, "services",
                prices.buildPerServiceMonth / 1000.0,
                "engineering time to construct and carry this design, spread over its life");
    }

    // --------------------------------------------------------------- quantities

    /** The projected value, the observed one, or null where the engine refused. */
    private static Double quantity(Map<String, Map<String, Object>> projections,
                                   String resource, double observed) {
        if (projections == null) return observed;
        var p = projections.get(resource);
        if (p == null) return observed;
        if (p.containsKey("projected")) return num(p.get("projected"));
        return null;
    }

    private static double projectedOrNaN(Map<String, Map<String, Object>> projections,
                                         String resource) {
        var p = projections.get(resource);
        if (p == null || !p.containsKey("projected")) return Double.NaN;
        return num(p.get("projected"));
    }

    /**
     * How much more of something there is at full scale than was measured, or NaN
     * where the engine would not say.
     */
    private static double growth(Map<String, Map<String, Object>> projections, String resource) {
        var p = projections.get(resource);
        if (p == null) return 1.0;                       // nothing is being projected here
        if (!p.containsKey("projected")) return Double.NaN;
        double observed = num(p.get("observed"));
        return observed <= 0 ? 1.0 : num(p.get("projected")) / observed;
    }

    private static String refusal(Map<String, Map<String, Object>> projections, String resource) {
        if (projections == null) return "it was not measured";
        var p = projections.get(resource);
        return p == null ? "it was not measured" : String.valueOf(p.get("refused"));
    }

    /** What one machine costs an hour, from the catalogue rather than the price list. */
    private static double rate(String instance) {
        try { return InstanceCatalog.get(instance).onDemandPerHour(); }
        catch (RuntimeException e) { return 0.10; }
    }

    private static Set<String> reclaimed(List<Map<String, Object>> events) {
        var out = new TreeSet<String>();
        for (var e : events)
            if ("spot_notice".equals(e.get("kind"))) out.add(String.valueOf(e.get("vm")));
        return out;
    }

    // ------------------------------------------------------------------ reading

    private static long count(List<Map<String, Object>> events, String kind) {
        return events.stream().filter(e -> kind.equals(e.get("kind"))).count();
    }

    /**
     * What the talking cost, priced by how far it went.
     *
     * <p>Up to three lines, because there are three prices. A megabyte moved inside
     * one region and a megabyte moved across an ocean are the same bytes and not
     * remotely the same bill, and a single summed line hides exactly the decision —
     * which region a replica goes in — that this is worth putting on a bill to inform.
     *
     * <p>Reads {@code egressMb} on each machine, which is the same bytes as
     * {@code crossZoneMb} split by where they went. A trace written before that
     * existed has no split, so it is billed whole at the same-region rate — the
     * old behaviour exactly, rather than a guess at a distance nobody recorded.
     */
    private static void egress(PnL pnl, List<Map<String, Object>> machines,
                               double crossZoneMb, double growth, PriceList prices) {
        var mbByLink = new EnumMap<losim.res.Regions.Link, Double>(losim.res.Regions.Link.class);
        double split = 0;
        for (Map<String, Object> m : machines) {
            String zone = String.valueOf(m.getOrDefault("zone", ""));
            for (var e : sub(m, "egressMb").entrySet()) {
                double mb = num(e.getValue());
                if (mb <= 0) continue;
                split += mb;
                mbByLink.merge(losim.res.Regions.toRegion(zone, e.getKey()), mb, Double::sum);
            }
        }
        if (split <= 0) {
            pnl.add("consumption", "cross-zone egress", crossZoneMb * growth / 1024.0, "GB",
                    prices.egressPerGb,
                    "traffic between availability zones is billed; traffic inside one is free");
            return;
        }
        for (var link : losim.res.Regions.Link.values()) {
            double mb = mbByLink.getOrDefault(link, 0.0);
            if (mb <= 0) continue;
            pnl.add("consumption", LINE.get(link), mb * growth / 1024.0, "GB",
                    prices.egressPerGb(link), WHY.get(link));
        }
    }

    /**
     * What each distance is called on a bill.
     *
     * <p>The same-region line keeps the name it has always had. The viewer matches
     * these labels to decide what a line is, and a bill somebody has already read
     * should not rename a row it has been printing for a year.
     */
    private static final Map<losim.res.Regions.Link, String> LINE = Map.of(
            losim.res.Regions.Link.SAME_REGION, "cross-zone egress",
            losim.res.Regions.Link.CROSS_REGION, "egress to another region",
            losim.res.Regions.Link.INTERCONTINENTAL, "egress across an ocean");

    private static final Map<losim.res.Regions.Link, String> WHY = Map.of(
            losim.res.Regions.Link.SAME_REGION,
            "between availability zones in one region; traffic inside one zone is free",
            losim.res.Regions.Link.CROSS_REGION,
            "to another region on the same continent, at roughly twice the in-region rate",
            losim.res.Regions.Link.INTERCONTINENTAL,
            "across an ocean, at many times the in-region rate — this is the line a "
                    + "badly placed replica shows up on");

    private static double sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToDouble(m -> num(m.get(key))).sum();
    }

    private static double peak(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToDouble(m -> num(m.get(key))).max().orElse(0);
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.getOrDefault(key, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
