# A project shaped like the grpc-java quickstart

This fixture follows the structure that `dissaly adopt` checks.
A server that binds a port with the handler nested inside it, a client that dials
a host, a `.proto` with no idempotency on anything, and a Gradle build with the
`application` plugin and `grpc-netty-shaded`.

Every one of those is a finding, and this fixture exists so that they are findings
in a test rather than the first time somebody runs the command on their own work.
