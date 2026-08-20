package losim.price;

import losim.scenario.Node;
import losim.scenario.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prices are course data, never library data — so a price list can be updated,
 * or deliberately distorted for an exercise, without touching the simulator.
 */
public final class PriceList {

    public String currency = "CHF";
    public final Map<String, Double> instancePerHour = new LinkedHashMap<>();
    public double spotDiscount = 0.70;
    public double egressPerGb = 0.01;
    public double storagePerGbMonth = 0.10;
    public long billingMinimumSeconds = 60;
    public double buildPerProgramMonth = 250.0;
    public double revenuePerJob = 5.0;
    public double slaSeconds = 10;
    public double latePenaltyPerSecond = 0.50;
    public double incidentPerRerun = 0.02;
    public double incidentPerLostItem = 1.0;

    public static PriceList defaults() { return new PriceList(); }

    public static PriceList load(Path p) throws IOException {
        return parse(Files.readString(p), p.getFileName().toString());
    }

    public static PriceList parse(String text, String file) {
        Node root = Yaml.parse(text, file);
        PriceList pl = new PriceList();
        if (root.opt("currency") != null) pl.currency = root.get("currency").str();
        if (root.opt("spot_discount") != null) pl.spotDiscount = root.get("spot_discount").number();
        if (root.opt("egress_per_gb") != null) pl.egressPerGb = root.get("egress_per_gb").number();
        if (root.opt("storage_per_gb_month") != null) pl.storagePerGbMonth = root.get("storage_per_gb_month").number();
        if (root.opt("billing_minimum_seconds") != null) pl.billingMinimumSeconds = root.get("billing_minimum_seconds").integer();
        if (root.opt("build_per_program_month") != null) pl.buildPerProgramMonth = root.get("build_per_program_month").number();
        if (root.opt("revenue_per_job") != null) pl.revenuePerJob = root.get("revenue_per_job").number();
        if (root.opt("sla_seconds") != null) pl.slaSeconds = root.get("sla_seconds").number();
        if (root.opt("late_penalty_per_second") != null) pl.latePenaltyPerSecond = root.get("late_penalty_per_second").number();
        if (root.opt("incident_per_rerun") != null) pl.incidentPerRerun = root.get("incident_per_rerun").number();
        if (root.opt("incident_per_lost_item") != null) pl.incidentPerLostItem = root.get("incident_per_lost_item").number();
        Node inst = root.opt("instances");
        if (inst != null) for (Map.Entry<String, Node> e : inst.map().entrySet())
            pl.instancePerHour.put(e.getKey(), e.getValue().number());
        return pl;
    }

    public double perHour(String instance, String market, double fallback) {
        double base = instancePerHour.getOrDefault(instance, fallback);
        return market.equals("spot") ? base * (1 - spotDiscount) : base;
    }
}
