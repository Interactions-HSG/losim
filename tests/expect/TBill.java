import java.util.List;
import java.util.Map;
import losim.price.Bill;
import losim.price.PnL;
import losim.price.PriceList;

/**
 * bill — the five-bucket account, over the metrics a run actually produces.
 *
 * <p><b>Catches:</b> the bill drifting away from the trace. It is the one consumer
 * that reads almost every channel — the machines and what they were, the events that
 * are incidents, the duration, and in scaled mode the projections — so a quantity
 * that stops being written stops being billed, silently and plausibly.
 *
 * <p>It also holds the line that matters most about money: <b>a bucket nobody could
 * fill in is not a bucket that cost nothing.</b> At full scale the capacity line
 * depends on the timeline, and on handlers this short the engine refuses the
 * timeline — so the largest line on the bill is absent with a reason, and the total
 * says it is a total of what could be priced.
 *
 * <p>Run against the traces t10 already wrote, so it costs nothing to include.
 */
public final class TBill {

    public static void main(String[] args) throws Exception {
        var e = Expect.of("bill", args);
        var prices = PriceList.load(java.nio.file.Path.of("prices/eu-central-1.yaml"));
        e.check(prices.currency != null && prices.egressPerGb > 0,
                "the price list is course data, loaded from prices/eu-central-1.yaml — a course "
                + "can make egress ruinous and watch which designs stop being sensible, without "
                + "touching the simulator");

        var scaled = Bill.of(read(args[0]), prices);
        var direct = Bill.of(read(args[1]), prices);

        var observed = direct.observed();
        e.check(observed.items().stream().map(i -> i.bucket()).distinct().count() >= 3
                && observed.byBucket().keySet().equals(new java.util.LinkedHashSet<>(PnL.BUCKETS)),
                "a direct run bills into the five buckets, reported apart rather than summed — "
                + "replication triples capacity and adds to build in order to empty incidents, "
                + "and one number cannot say that");

        double capacity = observed.byBucket().get("capacity");
        long machines = read(args[1]).get("machines") instanceof List<?> l ? l.size() : 0;
        e.check(capacity > 0 && observed.items().stream()
                        .filter(i -> i.bucket().equals("capacity")).count() == machines,
                "capacity is a line per machine at its catalogue rate — an idle machine costs "
                + "exactly as much as a busy one, which is only visible if each one is its own "
                + "line (" + machines + " machines, " + String.format("%.4f", capacity)
                + " " + prices.currency + ")");

        // Priced in reference time. The run took a few seconds of somebody's
        // afternoon; the job it models takes what the simulated clock says, and that
        // is the number anyone would be invoiced for.
        double hours = observed.items().stream()
                .filter(i -> i.bucket().equals("capacity")).mapToDouble(i -> i.quantity())
                .max().orElse(0);
        double floorHours = prices.billingMinimumSeconds / 3600.0;
        e.check(hours >= floorHours - 1e-9, String.format(
                "and it is billed in reference time against a %ds floor, not in the seconds the "
                + "laptop spent: %.5f machine-hours. A design that starts forty machines to do "
                + "four seconds of work pays for a minute of forty",
                prices.billingMinimumSeconds, hours));

        // The point of the whole thing.
        var projected = scaled.projected();
        e.check(projected != null, "a scaled run is billed twice: for what happened, and for the "
                + "job it is a model of");
        // The capacity line is priced, or it is absent *and says why* — never
        // quietly missing. Which of the two happens depends on the timeline,
        // because machine-hours need a duration: refuse the timeline law and the
        // largest line on the bill is the one nobody can fill in.
        //
        // This used to assert the second branch outright, on the assumption that
        // the timeline is always refused. That held while Clock.spend left a
        // quarter of every declared cost owed to the next span — no two seed sets
        // agreed on a duration, so the law never fitted. Costs are paid in full
        // now, the wobble sometimes lands inside the usable error bar, and the
        // engine fills the line in. A bill that got *more* complete is not a
        // regression, and a case that fails when it happens is testing the bug.
        //
        // What must not happen either way is a line that is simply not there.
        boolean capacityPriced = projected != null && projected.complete();
        boolean capacityExplained = projected != null
                && projected.unpriceable().containsKey("the fleet, for the period");
        e.check(capacityPriced || capacityExplained,
                "and the projected account either prices the capacity line or leaves it absent "
                + "with a reason — the largest line on the bill is never silently missing, "
                + "which is not where most people would look for the uncertainty in what a "
                + "design costs");
        e.note(capacityPriced
                ? "the timeline fitted, so the fleet's period is priced"
                : "the timeline was refused, so the largest line is absent and says so");
        if (projected != null)
            projected.unpriceable().values().forEach(e::note);

        // A projected consumption line, so the bill is not merely refusing everything.
        // Against the same run's own observed column, not against the direct run's:
        // the claim is that the engine billed the bigger job, and the comparison that
        // says so is between the two scales of one bill.
        boolean grew = projected != null && projected.items().stream()
                .filter(i -> i.bucket().equals("consumption"))
                .anyMatch(i -> scaled.observed().items().stream()
                        .filter(o -> o.what().equals(i.what()))
                        .anyMatch(o -> i.quantity() > o.quantity() * 1.5));
        e.check(grew, "while what it burns is priced at full scale and is duly larger — the "
                + "quantities the engine could project are billed, and only the ones it could "
                + "not are missing");
        e.done();
    }

    static Map<String, Object> read(String path) throws Exception {
        return losim.trace.JsonReader.readObject(
                java.nio.file.Files.readString(java.nio.file.Path.of(path)));
    }
}
