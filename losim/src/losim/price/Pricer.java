package losim.price;

import java.util.List;
import java.util.Map;

/**
 * Turns a run into a bill. Generic over labs: it reads only the fleet and the
 * metrics every run produces, so any lab gets a cost view without writing one.
 */
public final class Pricer {

    private Pricer() {}

    @SuppressWarnings("unchecked")
    public static PnL price(Map<String, Object> meta, PriceList pl) {
        PnL pnl = new PnL(pl.currency);

        long endedMs = num(meta.get("endedAtMs"));
        Map<String, Object> metrics = (Map<String, Object>) meta.getOrDefault("metrics", Map.of());
        List<Map<String, Object>> vms = (List<Map<String, Object>>) meta.getOrDefault("vms", List.of());

        // ---- capacity: you pay for a machine that sits idle exactly as much as a busy one
        double billedSeconds = Math.max(pl.billingMinimumSeconds, Math.ceil(endedMs / 1000.0));
        for (Map<String, Object> vm : vms) {
            String instance = String.valueOf(vm.get("instance"));
            String market = String.valueOf(vm.get("market"));
            double perHour = pl.perHour(instance, market, 0.10);
            pnl.add("capacity", vm.get("name") + " (" + instance + (market.equals("spot") ? ", spot" : "") + ")",
                    billedSeconds / 3600.0, "machine-hours", perHour,
                    "reserved for the whole period, billed per second with a "
                            + pl.billingMinimumSeconds + "s minimum");
        }

        // ---- consumption: what the work actually burned
        long crossZone = num(metrics.get("crossZoneBytes"));
        if (crossZone > 0)
            pnl.add("consumption", "cross-zone egress", crossZone / 1e9, "GB", pl.egressPerGb,
                    "traffic between availability zones is billed; traffic within one is free");

        long storedBytes = 0;
        for (Map<String, Object> vm : vms) storedBytes += num(vm.get("memPeak")) / 8;   // spilled state proxy
        if (storedBytes > 0)
            pnl.add("consumption", "storage", storedBytes / 1e9, "GB-month", pl.storagePerGbMonth,
                    "intermediate data held on local disks");

        // ---- incidents: zero until something breaks, then large
        long timeouts = num(metrics.get("rpcTimeouts"));
        if (timeouts > 0)
            pnl.add("incidents", "reruns after a timeout", timeouts, "reruns", pl.incidentPerRerun,
                    "work redone because a worker did not answer in time");

        long kills = num(metrics.get("kills"));
        if (kills > 0)
            pnl.add("incidents", "machines lost", kills, "failures", pl.incidentPerRerun * 10,
                    "recovering from a dead machine");

        double lateSeconds = Math.max(0, endedMs / 1000.0 - pl.slaSeconds);
        if (lateSeconds > 0)
            pnl.add("incidents", "late finish", lateSeconds, "seconds", pl.latePenaltyPerSecond,
                    "past the " + pl.slaSeconds + "s service level");

        // ---- build: carried whether or not it is ever needed
        int programs = 0;
        for (Map<String, Object> vm : vms) programs += ((List<?>) vm.getOrDefault("programs", List.of())).size();
        pnl.add("build", "programs deployed", programs, "programs", pl.buildPerProgramMonth / 1000.0,
                "engineering time to construct and carry this design");

        // ---- revenue: only when it works
        boolean finished = Boolean.TRUE.equals(meta.get("finished"));
        pnl.add("revenue", finished ? "job completed" : "job did not complete", finished ? 1 : 0, "jobs",
                pl.revenuePerJob, "the service earns only when it works");

        return pnl;
    }

    static long num(Object o) { return o instanceof Number n ? n.longValue() : 0; }
}
