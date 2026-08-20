package losim.api;

import java.lang.annotation.*;

/** Fires on a scheduled virtual-clock deadline. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnTimer {
    String name() default "";
    long everyMs() default 0;
}
