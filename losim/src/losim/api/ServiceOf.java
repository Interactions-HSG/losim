package losim.api;

import java.lang.annotation.*;

/** Marks a generated client (peer) interface as the caller side of a service. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ServiceOf {
    Class<?> value();
}
