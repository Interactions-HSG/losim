package losim.price;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of the bill.
 *
 * It carries the technical quantity it was computed from, so a bill reads
 * "23,400 messages x CHF 0.09", never a bare "CHF 2,106". Money is the
 * aggregator, never the replacement: a student who only ever sees francs
 * learns to optimise a cost function, which is the opposite of the point.
 */
public record LineItem(String bucket, String what, double quantity, String unit,
                       double unitPrice, double amount, String why) {

    public String render(String currency) {
        return String.format("%-12s %-26s %12s %-14s x %8.4f = %s %9.4f",
                bucket, what, trim(quantity), unit, unitPrice, currency, amount);
    }

    static String trim(double d) {
        if (d == Math.rint(d)) return String.format("%,d", (long) d);
        return String.format("%,.2f", d);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bucket", bucket);
        m.put("what", what);
        m.put("quantity", quantity);
        m.put("unit", unit);
        m.put("unitPrice", unitPrice);
        m.put("amount", amount);
        m.put("why", why);
        return m;
    }
}
