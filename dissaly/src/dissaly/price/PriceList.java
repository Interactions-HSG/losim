package dissaly.price;

import java.io.IOException;
import java.nio.file.Path;
import dissaly.sim.Field;
import dissaly.sim.Yaml;

/**
 * Prices are course data, never library data.
 *
 * <p>So a price list can be updated, or deliberately distorted for an exercise —
 * make egress ruinous and watch which designs stop being sensible — without touching
 * the simulator. It lives outside {@code dissaly/} for exactly that reason.
 *
 * <p><b>What a machine costs to rent is not here.</b> That belongs to the instance
 * catalogue, beside its vCPUs and its memory, because it is a property of the
 * machine rather than a choice the course makes. Two places declaring what an
 * r5.large costs would be two places to drift apart, and the drift would be silent.
 */
public final class PriceList {

    /**
     * The region these rates are for, when the file says.
     *
     * <p>Bookkeeping and nothing else: it names the file in the bill's own words,
     * so a bill from `prices/ap-northeast-1.yaml` cannot be mistaken for one from
     * Frankfurt. Where a machine actually is comes from the simulation, never from
     * here — a price list that could move a cluster would be a second simulation.
     */
    public String region = "";

    public String currency = "CHF";
    /** What a reclaimable machine costs, as a fraction off the on-demand rate. */
    public double spotDiscount = 0.70;
    /**
     * Egress, at three distances.
     *
     * <p>One rate could not describe this: a byte to the zone next door, a byte to
     * another region on the same continent, and a byte across an ocean are three
     * different charges from every cloud there is, and a design that talks
     * intercontinentally because nobody looked is one of the more expensive
     * mistakes this course can teach cheaply. Traffic inside one zone is free and
     * so has no rate here.
     */
    public double egressPerGb = 0.01;
    public double egressCrossRegionPerGb = 0.02;
    public double egressIntercontinentalPerGb = 0.09;

    /**
     * Bytes between two machines in one zone: half what it costs to cross one.
     *
     * <p>Not free, which is a deliberate departure from the sticker price. A cloud
     * carries two instances in one zone for nothing only over a *private* address
     * with nothing in the path — the same two over a public or elastic address are
     * billed at the cross-zone rate in both directions, and a real cluster's
     * traffic passes a load balancer, a gateway or a mesh often enough that free
     * is the exception. A course that wants the sticker price sets this to zero.
     *
     * <p>Cheaper than the zone next door rather than equal to it, so the distance
     * a design puts between its nodes still costs it something to get wrong.
     */
    public double egressSameZonePerGb = 0.005;

    /**
     * Per gigabyte the cluster carried, whatever distance it went.
     *
     * <p>Zero by default and not a distance at all: it is whatever sits in the
     * path. A NAT gateway bills about 0.045 a gigabyte processed <i>on top of</i>
     * transfer, and a load balancer, a VPC endpoint or a mesh's sidecar each bill
     * by the gigabyte in their own way — none of which care which zone the bytes
     * were going to. Set it and every byte on the wire pays a toll.
     */
    public double natPerGb = 0.0;
    public double storagePerGbMonth = 0.10;
    public long billingMinimumSeconds = 60;
    public double buildPerServiceMonth = 250.0;
    public double slaSeconds = 10;

    /**
     * How much operating time one second of the simulated clock stands for.
     *
     * <p>A run is a few minutes of somebody's afternoon standing in for a system
     * that runs continuously. Billed at its own length, every line comes out in
     * thousandths of a rappen — a cluster of five for two minutes is four
     * centimes — and a bill nobody can read is a bill nobody argues with, which
     * is the only thing a bill is for here.
     *
     * <p>So the clock is scaled and the rates are left alone: a second of the
     * film is a day of operation, and an m5.2xlarge still costs what an
     * m5.2xlarge costs per hour. Scaling the rates instead would have made every
     * price on the page unrecognisable, and the one thing a student should be
     * able to check against a cloud's own price page is the rate.
     *
     * <p>Applies to everything time carries with it. Machine-hours and the months
     * a spill is held for, plainly — and the traffic too, because a flow is a rate
     * times a period: a cluster billed for a hundred days of machine time that
     * carried a third of a megabyte in those hundred days is not a system anybody
     * could build. What it is <i>holding</i> does not scale, because that is a
     * level and not a flow: the spill is the same spill, kept for longer.
     */
    public double billedDaysPerSecond = 1.0;

    public static PriceList defaults() { return new PriceList(); }

    /**
     * The rates, as data.
     *
     * <p>So that anything computing money from a trace computes it from the same
     * numbers this did, rather than from a copy of them that will drift.
     */
    public java.util.Map<String, Object> asMap() {
        var m = new java.util.LinkedHashMap<String, Object>();
        if (!region.isEmpty()) m.put("region", region);
        m.put("currency", currency);
        m.put("spotDiscount", spotDiscount);
        m.put("egressPerGb", egressPerGb);
        m.put("egressCrossRegionPerGb", egressCrossRegionPerGb);
        m.put("egressIntercontinentalPerGb", egressIntercontinentalPerGb);
        m.put("egressSameZonePerGb", egressSameZonePerGb);
        m.put("natPerGb", natPerGb);
        m.put("storagePerGbMonth", storagePerGbMonth);
        m.put("billingMinimumSeconds", billingMinimumSeconds);
        m.put("buildPerServiceMonth", buildPerServiceMonth);
        m.put("slaSeconds", slaSeconds);
        m.put("billedDaysPerSecond", billedDaysPerSecond);
        return m;
    }

    public static PriceList load(Path p) throws IOException {
        return of(Yaml.parse(p));
    }

    /**
     * The list of this name that ships inside the jar, or null if there is none.
     *
     * <p>Every list in {@code prices/} is a resource of the jar as well as a file
     * in this repository, and a lab has only the first — it resolves dissaly from
     * Maven and carries no copy of anything dissaly ships. Before this, such a lab
     * silently billed at the built-in defaults and said so in one line on stderr —
     * correct for Frankfurt, which is what the defaults are, and quietly wrong for
     * anyone who asked for a different region.
     *
     * <p>Looked up by name rather than by path so that {@code --prices} keeps
     * naming a file first: a list somebody wrote and put on disk is theirs, and a
     * built-in of the same name must not take precedence over it.
     */
    public static PriceList bundled(String name) {
        try (var in = PriceList.class.getResourceAsStream("/dissaly/prices/" + name)) {
            if (in == null) return null;
            return of(Yaml.parse(name, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        } catch (IOException e) {
            // A resource that will not read is not a price list, and the caller has
            // a defensible fallback. Saying nothing here keeps that decision in one
            // place rather than two.
            return null;
        }
    }

    public static PriceList of(Field root) {
        root.onlyAllows("region", "currency", "spot_discount", "egress_per_gb",
                "egress_cross_region_per_gb", "egress_intercontinental_per_gb",
                "egress_same_zone_per_gb", "nat_per_gb",
                "storage_per_gb_month",
                "billing_minimum_seconds", "build_per_service_month",
                "sla_seconds", "billed_days_per_second");
        var pl = new PriceList();
        pl.region = root.opt("region").str(pl.region);
        pl.currency = root.opt("currency").str(pl.currency);
        pl.spotDiscount = root.opt("spot_discount").num(pl.spotDiscount);
        pl.egressPerGb = root.opt("egress_per_gb").num(pl.egressPerGb);
        pl.egressCrossRegionPerGb =
                root.opt("egress_cross_region_per_gb").num(pl.egressCrossRegionPerGb);
        pl.egressIntercontinentalPerGb =
                root.opt("egress_intercontinental_per_gb").num(pl.egressIntercontinentalPerGb);
        pl.egressSameZonePerGb = root.opt("egress_same_zone_per_gb").num(pl.egressSameZonePerGb);
        pl.natPerGb = root.opt("nat_per_gb").num(pl.natPerGb);
        pl.storagePerGbMonth = root.opt("storage_per_gb_month").num(pl.storagePerGbMonth);
        pl.billingMinimumSeconds = root.opt("billing_minimum_seconds")
                .integer((int) pl.billingMinimumSeconds);
        pl.buildPerServiceMonth = root.opt("build_per_service_month").num(pl.buildPerServiceMonth);
        pl.slaSeconds = root.opt("sla_seconds").num(pl.slaSeconds);
        pl.billedDaysPerSecond = root.opt("billed_days_per_second").num(pl.billedDaysPerSecond);
        return pl;
    }

    /**
     * What a gigabyte costs to send, at the distance it actually went.
     *
     * <p>{@link dissaly.res.Regions.Link#SAME_ZONE} is free unless a course says
     * otherwise, which is why cross-zone bytes are counted apart from the rest as
     * they happen rather than derived at the end.
     */
    public double egressPerGb(dissaly.res.Regions.Link link) {
        return switch (link) {
            case SAME_ZONE -> egressSameZonePerGb;
            case SAME_REGION -> egressPerGb;
            case CROSS_REGION -> egressCrossRegionPerGb;
            case INTERCONTINENTAL -> egressIntercontinentalPerGb;
        };
    }

    /** How much operation one second of the run stands for, as a plain multiplier. */
    public double billedFactor() {
        return billedDaysPerSecond * 86400.0;
    }

    /** How many seconds of operation a run of this many reference milliseconds is billed as. */
    public double billedSeconds(double refMs) {
        return Math.max(billingMinimumSeconds, (refMs / 1000.0) * billedFactor());
    }

    /** The same period in months, which is what storage and engineering time are priced in. */
    public double billedMonths(double refMs) {
        return billedSeconds(refMs) / (30.0 * 86400.0);
    }

    /** What one machine costs per hour, discounted if it turned out to be reclaimable. */
    public double perHour(double onDemand, boolean spot) {
        return spot ? onDemand * (1 - spotDiscount) : onDemand;
    }
}
