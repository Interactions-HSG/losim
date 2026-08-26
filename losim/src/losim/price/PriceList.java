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

    public String currency = "CHF";
    /** What a reclaimable machine costs, as a fraction off the on-demand rate. */
    public double spotDiscount = 0.70;
    public double egressPerGb = 0.01;
    public double storagePerGbMonth = 0.10;
    public long billingMinimumSeconds = 60;
    public double buildPerServiceMonth = 250.0;
    public double revenuePerJob = 5.0;
    public double slaSeconds = 10;
    public double latePenaltyPerSecond = 0.50;
    public double incidentPerRerun = 0.02;
    public double incidentPerLostMachine = 0.20;

    public static PriceList defaults() { return new PriceList(); }

    public static PriceList load(Path p) throws IOException {
        return of(Yaml.parse(p));
    }

    public static PriceList of(Node root) {
        root.onlyAllows("currency", "spot_discount", "egress_per_gb", "storage_per_gb_month",
                "billing_minimum_seconds", "build_per_service_month", "revenue_per_job",
                "sla_seconds", "late_penalty_per_second", "incident_per_rerun",
                "incident_per_lost_machine");
        var pl = new PriceList();
        pl.currency = root.opt("currency").str(pl.currency);
        pl.spotDiscount = root.opt("spot_discount").num(pl.spotDiscount);
        pl.egressPerGb = root.opt("egress_per_gb").num(pl.egressPerGb);
        pl.storagePerGbMonth = root.opt("storage_per_gb_month").num(pl.storagePerGbMonth);
        pl.billingMinimumSeconds = root.opt("billing_minimum_seconds")
                .integer((int) pl.billingMinimumSeconds);
        pl.buildPerServiceMonth = root.opt("build_per_service_month").num(pl.buildPerServiceMonth);
        pl.revenuePerJob = root.opt("revenue_per_job").num(pl.revenuePerJob);
        pl.slaSeconds = root.opt("sla_seconds").num(pl.slaSeconds);
        pl.latePenaltyPerSecond = root.opt("late_penalty_per_second").num(pl.latePenaltyPerSecond);
        pl.incidentPerRerun = root.opt("incident_per_rerun").num(pl.incidentPerRerun);
        pl.incidentPerLostMachine = root.opt("incident_per_lost_machine")
                .num(pl.incidentPerLostMachine);
        return pl;
    }

    /** What one machine costs per hour, discounted if it turned out to be reclaimable. */
    public double perHour(double onDemand, boolean spot) {
        return spot ? onDemand * (1 - spotDiscount) : onDemand;
    }
}
