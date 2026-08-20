/** A message is just a record. No schema, no codegen, no toolchain. */
public record Token(String text, int hops) {}
