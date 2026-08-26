package losim.api;

import java.lang.annotation.*;

/**
 * How long this method's work takes on the reference machine.
 *
 * "2 ms" names no machine and does not compose with scaling, so every declared
 * duration in losim is reference-machine time (D3). The interceptor sleeps
 * {@code refMs * machineFactor / k_time}: a machine with half the reference's
 * vCPUs takes twice as long, and the whole run is compressed by k_time so a
 * forty-minute job lands in tens of seconds.
 *
 * <p><b>Why this has to be declared at all, when everything else is measured.</b>
 * losim shrinks the workload and the machines by the same factor, so memory and
 * bytes survive it honestly: a reducer that would exhaust a 16 GiB machine
 * exhausts a 16 MiB one, in the program's own code, for the same reason. Time does
 * not survive it, because the host's CPU is not shrunk — it runs at full speed on a
 * five-thousandth of the data, so every handler is genuinely instant. Without a
 * declared duration there is no queueing, no contention, no deadline pressure and
 * no critical path, which is most of what a fleet is interesting for.
 *
 * <p>Measuring it instead does not work at this scale: handler durations at probe
 * size sit inside the host's own jitter, where the fitted time exponent moves by
 * 0.25 between seed sets of an identical workload. So this is declared — and it is
 * <b>optional</b>: an unannotated method takes no time, deliberately, rather than a
 * made-up amount.
 *
 * <p>The two terms answer different questions. {@link #refMs()} is what the call
 * takes regardless of what is in it — the fixed part, known before the handler
 * runs, and slept before it. {@link #refNsPerRecord()} is what it takes per unit
 * of input, which nothing outside the handler can know: the handler declares the
 * count with {@link LosimCtx#records(long)} and the variable part is slept after
 * the body returns, before the span closes. A handler that never declares a
 * count is charged the fixed part only, and its span says so.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Takes {
    /** What one call takes regardless of what is in it, in reference milliseconds. */
    double refMs() default 0;

    /** What each record processed adds, in reference nanoseconds. */
    double refNsPerRecord() default 0;
}
