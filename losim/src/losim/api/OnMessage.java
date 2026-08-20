package losim.api;

import java.lang.annotation.*;

/** Style A: an inbound message handler, dispatched by its message parameter type. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnMessage { }
