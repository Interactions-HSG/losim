package losim.api;

import java.lang.annotation.*;

/**
 * Graceful shutdown notice. Fires for a spot reclaim; never for a kill —
 * which is exactly why correctness cannot rest on it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnTerminate { }
