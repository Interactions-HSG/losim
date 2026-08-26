package losim.runtime;

import io.grpc.MethodDescriptor;
import java.util.List;

/**
 * How many times to try again, and what the schema had to say about it.
 *
 * <p>Retrying is an operational decision, so it lives in the scenario where it can
 * be diffed and swept. Whether retrying is <i>safe</i> is a property of the method,
 * so it lives in the {@code .proto} as {@code option idempotency_level}. The two
 * have to agree, and losim refuses at load when they do not.
 *
 * <p>A scenario can still retry a method the schema calls unsafe, by writing
 * {@code unsafe: true}. That is the point: it makes "we retry a call that is not
 * safe to run twice" one visible line in a diff rather than an emergent property of
 * a configuration nobody read.
 */
public record Retry(String method, int attempts, double backoffRefMs,
                    double multiplier, boolean unsafe, String where) {

    /** Matches a bare method name, a dotted {@code Service.Method}, or {@code *}. */
    public boolean matches(MethodDescriptor<?, ?> md) {
        if (method.equals("*")) return true;
        String dotted = Wire.dotted(md.getFullMethodName());
        return dotted.equals(method)
            || dotted.endsWith("." + method)
            || dotted.substring(dotted.lastIndexOf('.') + 1).equals(method);
    }

    /**
     * Refuses a policy the schema does not support.
     *
     * @param known every method the fleet actually serves
     * @throws IllegalArgumentException naming the file and line, because a
     *         configuration error should read like a compiler error
     */
    public void checkAgainst(List<MethodDescriptor<?, ?>> known) {
        var matched = known.stream().filter(this::matches).toList();
        if (matched.isEmpty())
            throw new IllegalArgumentException(where + ": retry policy names '" + method
                    + "', which no machine in this fleet serves");
        if (unsafe) return;
        var unsound = matched.stream().filter(md -> !md.isIdempotent()).toList();
        if (unsound.isEmpty()) return;
        throw new IllegalArgumentException(where + ": retrying "
                + Wire.dotted(unsound.get(0).getFullMethodName())
                + " is refused — its .proto declares no idempotency_level, so running it twice"
                + " is not known to be safe. Declare"
                + " 'option idempotency_level = IDEMPOTENT;' on the rpc if it is,"
                + " or write 'unsafe: true' here if you mean to retry it anyway.");
    }

    /** Backoff before attempt {@code n}, counting the first attempt as 1. */
    public double backoffBefore(int n) {
        double b = backoffRefMs;
        for (int i = 2; i < n; i++) b *= multiplier;
        return b;
    }
}
