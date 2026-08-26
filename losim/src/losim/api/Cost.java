package losim.api;

import java.lang.annotation.*;

/**
 * What this method costs on the reference machine.
 *
 * "2 ms" names no machine and does not compose with scaling, so every declared
 * duration in losim is reference-machine time (D3). The interceptor sleeps
 * {@code refMs * machineFactor / k_time}: a machine with half the reference's
 * vCPUs takes twice as long, and the whole run is compressed by k_time so a
 * forty-minute job lands in tens of seconds.
 *
 * <p>The two terms answer different questions. {@link #refMs()} is what the call
 * costs regardless of what is in it — the fixed part, known before the handler
 * runs, and slept before it. {@link #refNsPerRecord()} is what it costs per unit
 * of input, which nothing outside the handler can know: the handler declares the
 * count with {@link LosimCtx#records(long)} and the variable part is slept after
 * the body returns, before the span closes. A handler that never declares a
 * count is charged the fixed part only, and its span says so.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Cost {
    /** Fixed cost of one call, in milliseconds on the reference machine. */
    double refMs() default 0;

    /** Cost per record processed, in nanoseconds on the reference machine. */
    double refNsPerRecord() default 0;
}
