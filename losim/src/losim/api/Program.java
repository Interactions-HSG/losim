package losim.api;

/**
 * What a student writes. A program runs on a VM.
 *
 * A program that only reacts to messages or RPCs needs no {@code main} — the
 * harness boots it, registers its handlers, and it waits. Override {@code main}
 * only when the program takes initiative.
 */
public interface Program {
    default void main(Ctx ctx) throws Exception { }
}
