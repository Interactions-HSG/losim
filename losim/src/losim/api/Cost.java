package losim.api;

import java.lang.annotation.*;

/** Declared execution cost. Portable across machines by construction. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Cost {
    long ms() default 0;
}
