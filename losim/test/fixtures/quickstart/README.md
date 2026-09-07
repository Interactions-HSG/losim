# A project shaped like the grpc-java quickstart

Not a copy of it: written to its shape, which is what `losim adopt` has to meet.
A server that binds a port with the handler nested inside it, a client that dials
a host, a `.proto` with no idempotency on anything, and a Gradle build with the
`application` plugin and `grpc-netty-shaded`.

Every one of those is a finding, and this fixture exists so that they are findings
in a test rather than the first time somebody runs the command on their own work.
