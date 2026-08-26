package losim.price;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of a bill, carrying its own quantity.
 *
 * <p>The quantity and the unit price are kept rather than only their product,
 * because the interesting question is almost never "how much" but "how much of
 * what". A capacity line that reads 4.2 machine-hours at 0.152 an hour can be
 * argued with; one that reads 0.64 cannot.
 *
 * @param why what this line is, in the words someone would use to decide about it
 */
public record LineItem(String bucket, String what, double quantity, String unit,
                       double unitPrice, double amount, String why) {

    public Map<String, Object> asMap() {
        var m = new LinkedHashMap<String, Object>();
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
