package losim.price;

import java.io.IOException;
import java.nio.file.Path;
import losim.scenario.Node;
import losim.scenario.Yaml;

/**
 * Prices are course data, never library data.
 *
 * <p>So a price list can be updated, or deliberately distorted for an exercise —
 * make egress ruinous and watch which designs stop being sensible — without touching
 * the simulator. It lives outside {@code losim/} for exactly that reason.
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
     * Frankfurt. Where a machine actually is comes from the scenario, never from
     * here — a price list that could move a fleet would be a second scenario.
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
    public double storagePerGbMonth = 0.10;
    public long billingMinimumSeconds = 60;
    public double buildPerServiceMonth = 250.0;
    public double slaSeconds = 10;
    public double latePenaltyPerSecond = 0.50;
    public double incidentPerRerun = 0.02;
    public double incidentPerLostMachine = 0.20;

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
        m.put("storagePerGbMonth", storagePerGbMonth);
        m.put("billingMinimumSeconds", billingMinimumSeconds);
        m.put("buildPerServiceMonth", buildPerServiceMonth);
        m.put("slaSeconds", slaSeconds);
        m.put("latePenaltyPerSecond", latePenaltyPerSecond);
        m.put("incidentPerRerun", incidentPerRerun);
        m.put("incidentPerLostMachine", incidentPerLostMachine);
        return m;
    }

    public static PriceList load(Path p) throws IOException {
        return of(Yaml.parse(p));
    }

    public static PriceList of(Node root) {
        root.onlyAllows("region", "currency", "spot_discount", "egress_per_gb",
                "egress_cross_region_per_gb", "egress_intercontinental_per_gb",
                "storage_per_gb_month",
                "billing_minimum_seconds", "build_per_service_month",
                "sla_seconds", "late_penalty_per_second", "incident_per_rerun",
                "incident_per_lost_machine");
        var pl = new PriceList();
        pl.region = root.opt("region").str(pl.region);
        pl.currency = root.opt("currency").str(pl.currency);
        pl.spotDiscount = root.opt("spot_discount").num(pl.spotDiscount);
        pl.egressPerGb = root.opt("egress_per_gb").num(pl.egressPerGb);
        pl.egressCrossRegionPerGb =
                root.opt("egress_cross_region_per_gb").num(pl.egressCrossRegionPerGb);
        pl.egressIntercontinentalPerGb =
                root.opt("egress_intercontinental_per_gb").num(pl.egressIntercontinentalPerGb);
        pl.storagePerGbMonth = root.opt("storage_per_gb_month").num(pl.storagePerGbMonth);
        pl.billingMinimumSeconds = root.opt("billing_minimum_seconds")
                .integer((int) pl.billingMinimumSeconds);
        pl.buildPerServiceMonth = root.opt("build_per_service_month").num(pl.buildPerServiceMonth);
        pl.slaSeconds = root.opt("sla_seconds").num(pl.slaSeconds);
        pl.latePenaltyPerSecond = root.opt("late_penalty_per_second").num(pl.latePenaltyPerSecond);
        pl.incidentPerRerun = root.opt("incident_per_rerun").num(pl.incidentPerRerun);
        pl.incidentPerLostMachine = root.opt("incident_per_lost_machine")
                .num(pl.incidentPerLostMachine);
        return pl;
    }

    /**
     * What a gigabyte costs to send, at the distance it actually went.
     *
     * <p>{@link losim.res.Regions.Link#SAME_ZONE} is free, which is why traffic
     * inside a zone is not counted at all — and why cross-zone bytes have to be
     * counted apart from the rest as they happen rather than derived at the end.
     */
    public double egressPerGb(losim.res.Regions.Link link) {
        return switch (link) {
            case SAME_ZONE -> 0.0;
            case SAME_REGION -> egressPerGb;
            case CROSS_REGION -> egressCrossRegionPerGb;
            case INTERCONTINENTAL -> egressIntercontinentalPerGb;
        };
    }

    /** What one machine costs per hour, discounted if it turned out to be reclaimable. */
    public double perHour(double onDemand, boolean spot) {
        return spot ? onDemand * (1 - spotDiscount) : onDemand;
    }
}
