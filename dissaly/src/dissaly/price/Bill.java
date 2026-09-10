package dissaly.price;

import java.util.*;
import dissaly.res.InstanceCatalog;

/**
 * Turning a run into a bill, at both of its scales.
 *
 * <p>Generic over labs: it reads only the trace, so any gRPC system gets a cost view
 * without anyone writing one for it.
 *
 * <h2>Two bills, and why the second has holes</h2>
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
 * <p>Events are never projected (D7). A machine either failed or it did not, and a
 * count extrapolated from one afternoon would be a forecast dressed as an
 * observation. That rule used to have a hole in it: `late finish` was computed from
 * the <i>projected</i> makespan and charged into the same bucket as the observed
 * counts, where it was 99% of the total. Nothing that broke is priced now, so
 * there is nothing left to project.
 */
public final class Bill {
    private Bill() {}

    /** Both accounts, and the projected one is absent where the run was not scaled. */
    public record Both(Account observed, Account projected) {

        public Map<String, Object> asMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("observed", observed.asMap());
            if (projected != null) m.put("projected", projected.asMap());
            return m;
        }
    }

    public static Both of(Map<String, Object> trace, PriceList prices) {
        var meta = sub(trace, "meta");
        var machines = rows(trace, "nodes");
        var events = rows(trace, "events");
        boolean scaled = "scaled".equals(meta.get("mode"));

        var observed = new Account(prices.currency, "observed");
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

        var projected = new Account(prices.currency, "projected");
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
    private static void price(Account account, PriceList prices, Map<String, Object> meta,
                              List<Map<String, Object>> machines,
                              List<Map<String, Object>> events,
                              double durationRefMs, double wireGrowth,
                              Map<String, Map<String, Object>> projections) {

        // --- capacity: an idle machine costs exactly as much as a busy one.
        // Priced in reference time, deliberately. The run took a few seconds of
        // somebody's afternoon; the job it is a model of takes what the simulated
        // clock says, and that is the number anyone would be invoiced for.
        if (Double.isNaN(durationRefMs)) {
            account.cannotPrice("capacity", "the cluster, for the period",
                    projections == null ? "the run has no duration"
                            : refusal(projections, "makespanRefMs"));
        } else {
            double seconds = prices.billedSeconds(durationRefMs);
            var spot = reclaimed(events);
            for (var m : machines) {
                String instance = String.valueOf(m.get("instance"));
                double onDemand = rate(instance);
                boolean onSpot = spot.contains(String.valueOf(m.get("name")));
                account.add("capacity", m.get("name") + " (" + instance
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
        // Traffic is a flow, so it carries the same scaling the period does: the
        // run stands for a longer stretch of operation, and the cluster went on
        // talking through all of it.
        double flow = prices.billedFactor();
        double crossZoneMb = sum(machines, "crossZoneMb") * flow;
        double wireMb = sum(machines, "wireMb") * flow;
        boolean charged = false;
        if (Double.isNaN(wireGrowth) && wireMb > 0) {
            account.cannotPrice("consumption", "traffic", refusal(projections, "wireMb"));
            charged = true;
        } else {
            if (crossZoneMb > 0) {
                egress(account, machines, crossZoneMb, wireGrowth * flow, prices);
                charged = true;
            }
            // Bytes that never left the zone. Free by default and priced when a
            // course says they are not — two machines one rack apart talking over
            // a public address are billed at the cross-zone rate in both
            // directions, and that surprise is worth being able to show.
            double sameZoneMb = Math.max(0, wireMb - crossZoneMb) * wireGrowth;
            if (prices.egressSameZonePerGb > 0 && sameZoneMb > 0) {
                account.add("consumption", "same-zone egress", sameZoneMb / 1024.0, "GB",
                        prices.egressSameZonePerGb,
                        "traffic inside one zone, billed: these bytes did not take"
                                + " the private path that makes them free");
                charged = true;
            }
            // Whatever sits in the path bills by the gigabyte and does not care
            // which zone the bytes were going to.
            if (prices.natPerGb > 0 && wireMb > 0) {
                account.add("consumption", "gateway processing", wireMb * wireGrowth / 1024.0, "GB",
                        prices.natPerGb,
                        "every gigabyte the cluster carried, charged by what stands in"
                                + " the path: a NAT gateway, a load balancer, a mesh");
                charged = true;
            }
        }
        // A cluster that talks inside one zone at the default rates has no
        // traffic line at all, and a bill with none on it reads as a bill that
        // forgot to count the bytes. Counted rather than left out: this much
        // moved, and nothing charged for it.
        if (!charged && wireMb > 0)
            account.count("traffic carried", wireMb * (Double.isNaN(wireGrowth) ? 1 : wireGrowth), "MB",
                    "every call stayed inside one zone, where egress is free");

        Double disk = quantity(projections, "diskMb", peak(machines, "diskMb"));
        if (disk == null)
            account.cannotPrice("consumption", "spilled data on disk",
                    refusal(projections, "diskMb"));
        else if (Double.isNaN(durationRefMs) && disk > 0)
            // A gigabyte-month is a gigabyte and a period, and the period here is
            // the one the engine would not project. Before the period entered this
            // line it charged a full month for a two-second spill, which needed no
            // timeline because it was not measuring one.
            account.cannotPrice("consumption", "spilled data on disk",
                    projections == null ? "the run has no duration"
                            : refusal(projections, "makespanRefMs"));
        else if (disk > 0)
            account.add("consumption", "spilled data on disk",
                    (disk / 1024.0) * prices.billedMonths(durationRefMs), "GB-month",
                    prices.storagePerGbMonth,
                    "the worst machine's spill, held for the period");

        // --- what happened: counted, and deliberately not priced. What a timeout
        // or a dead machine costs an organisation is a number this course does
        // not have, and the one it used to invent decided most of the bill.
        // D7 again: a count from one afternoon is not a forecast, so the projected
        // account gets none of them. What it does get is its own makespan, which is
        // a projection and says so.
        if (!"projected".equals(account.scale)) {
            account.count("calls that did not answer in time", count(events, "rpc_timeout"), "calls",
                    "a machine did not answer inside its deadline");
            account.count("machines lost", count(events, "kill") + count(events, "spot_notice"),
                    "machines", "went away mid-job");
            account.count("machines that filled up", count(events, "oom") + count(events, "disk_full"),
                    "machines", "sized too small for what its design asked it to hold");
        }
        if (!Double.isNaN(durationRefMs))
            account.count("late finish", Math.max(0, durationRefMs / 1000.0 - prices.slaSeconds),
                    "seconds", "past the " + prices.slaSeconds + "s service level");

        // --- build: carried whether or not it is ever needed, and carried for as
        // long as the design is up — so it is priced over the same period the
        // machines are, rather than over an arbitrary fraction of a month.
        var services = new TreeSet<String>();
        for (var m : machines)
            for (Object s : (List<?>) m.getOrDefault("serves", List.of()))
                services.add(String.valueOf(s));
        int distinct = Math.max(1, services.size());
        if (Double.isNaN(durationRefMs))
            account.cannotPrice("build", "services carried",
                    projections == null ? "the run has no duration"
                            : refusal(projections, "makespanRefMs"));
        else
            account.add("build", "services carried",
                    distinct * prices.billedMonths(durationRefMs), "service-months",
                    prices.buildPerServiceMonth,
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
     * {@code crossZoneMb} split by where they went. A trace with no split is
     * billed whole at the same-region rate, rather than a guess at a distance
     * nobody recorded.
     */
    /**
     * @param crossZoneMb already carrying the period's scaling; the fallback line
     *                    uses it as it is
     * @param growth      the wire law's growth <i>and</i> the period's scaling, for
     *                    the per-link quantities, which are read fresh from the
     *                    machines here rather than passed in
     */
    private static void egress(Account account, List<Map<String, Object>> machines,
                               double crossZoneMb, double growth, PriceList prices) {
        var mbByLink = new EnumMap<dissaly.res.Regions.Link, Double>(dissaly.res.Regions.Link.class);
        double split = 0;
        for (Map<String, Object> m : machines) {
            String zone = String.valueOf(m.getOrDefault("zone", ""));
            for (var e : sub(m, "egressMb").entrySet()) {
                double mb = num(e.getValue());
                if (mb <= 0) continue;
                split += mb;
                mbByLink.merge(dissaly.res.Regions.toRegion(zone, e.getKey()), mb, Double::sum);
            }
        }
        if (split <= 0) {
            account.add("consumption", "cross-zone egress", crossZoneMb * growth / 1024.0, "GB",
                    prices.egressPerGb,
                    "traffic between availability zones is billed; traffic inside one is free");
            return;
        }
        for (var link : dissaly.res.Regions.Link.values()) {
            double mb = mbByLink.getOrDefault(link, 0.0);
            if (mb <= 0) continue;
            account.add("consumption", LINE.get(link), mb * growth / 1024.0, "GB",
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
    private static final Map<dissaly.res.Regions.Link, String> LINE = Map.of(
            dissaly.res.Regions.Link.SAME_REGION, "cross-zone egress",
            dissaly.res.Regions.Link.CROSS_REGION, "egress to another region",
            dissaly.res.Regions.Link.INTERCONTINENTAL, "egress across an ocean");

    private static final Map<dissaly.res.Regions.Link, String> WHY = Map.of(
            dissaly.res.Regions.Link.SAME_REGION,
            "between availability zones in one region; traffic inside one zone is free",
            dissaly.res.Regions.Link.CROSS_REGION,
            "to another region on the same continent, at roughly twice the in-region rate",
            dissaly.res.Regions.Link.INTERCONTINENTAL,
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
