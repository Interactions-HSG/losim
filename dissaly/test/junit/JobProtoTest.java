import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import dissaly.cli.Lab;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * dissaly's own schema, as a project sees it.
 *
 * <p>A system implements {@code dissaly.Job} to be startable at all, so the schema
 * has to reach a project that has dissaly as its one dependency — and reach it as
 * exactly one set of classes. That second half is the whole reason this file
 * exists.
 *
 * <p><b>What goes wrong if it does not, and why nothing would say so.</b> Every
 * classloader dissaly builds delegates to its parent first, so a project that
 * generated its own {@code dissaly.pb.Input} would compile a class the jar's copy
 * then shadows — and the simulation would run, correctly, forever. What it costs is
 * a protoc run and a javac run producing classes nothing loads, in an output
 * directory that then claims to hold dissaly's own. What it breaks is everything that
 * counts a project's schemas rather than loading them: {@code dissaly check} would
 * report rpcs nobody wrote.
 *
 * <p>So the rule — {@code dissaly/job.proto} on protoc's include path and never in its
 * input list — has no symptom of its own. That is exactly why it needs a check:
 * asserted here as a file that must not exist, because there is nothing at run time
 * left to notice.
 */
class JobProtoTest {

    static Path root;
    static Lab lab;

    @BeforeAll
    static void buildLab() throws Exception {
        root = Fixture.build();

        // A schema that imports dissaly's, which is the case worth testing: an
        // import is the only way a project can end up compiling the same file
        // twice, and it resolves only if the include path reached it.
        Files.writeString(root.resolve("proto/frames.proto"), """
                syntax = "proto3";
                package lab;
                import "dissaly/job.proto";
                option java_package = "lab.pb";
                option java_multiple_files = true;
                message Frames { repeated string names = 1; }
                service Thumbnailer { rpc Thumbnail (Frames) returns (Frames); }
                """);

        // And a Job, written the way an assignment writes one: two rpcs, dissaly's
        // own generated types in the signatures, and no dissaly.api symbol at all.
        Files.writeString(root.resolve("src/RenderMaster.java"), """
                import io.grpc.stub.StreamObserver;
                import dissaly.pb.Input;
                import dissaly.pb.JobGrpc;
                import dissaly.pb.Result;
                import dissaly.pb.Workload;

                public final class RenderMaster extends JobGrpc.JobImplBase {
                    @Override public void load(Input in, StreamObserver<Workload> out) {
                        lab.pb.Frames frames = lab.pb.Frames.newBuilder()
                                .addNames(in.getSource()).build();
                        out.onNext(Workload.newBuilder()
                                .setCount(in.getCount())
                                .setType(lab.pb.Frames.getDescriptor().getFullName())
                                .setPayload(frames.toByteString())
                                .build());
                        out.onCompleted();
                    }

                    @Override public void run(Workload work, StreamObserver<Result> out) {
                        out.onNext(Result.newBuilder()
                                .putAnswer("units", String.valueOf(work.getCount())).build());
                        out.onCompleted();
                    }
                }
                """);

        lab = new Lab(root);
    }

    @AfterAll
    static void tearDown() throws Exception {
        Fixture.delete(root);
    }

    @Test
    @DisplayName("a schema that imports dissaly/job.proto compiles, and a Job over it compiles too")
    void resolvesAndCompiles() throws Exception {
        var log = new StringBuilder();
        assertNotNull(lab.compile(log::append), log.toString());
        assertTrue(Files.isRegularFile(lab.classes().resolve("RenderMaster.class")),
                "the Job class is not in the output, so it never compiled against dissaly.pb");
    }

    @Test
    @DisplayName("and generates no second copy of dissaly's own classes")
    void generatesNoSecondCopy() throws Exception {
        var log = new StringBuilder();
        assertNotNull(lab.compile(log::append), log.toString());

        // Nothing under gen/dissaly/pb, and nothing under classes/dissaly/pb. Both,
        // because either one alone would pass while the other was the problem:
        // a stale gen/ with no fresh compile, or a compile that read a gen/ this
        // assertion had already looked past.
        assertFalse(Files.exists(root.resolve("gen/dissaly/pb")),
                "protoc generated dissaly's own classes into the project — job.proto was passed as "
                + "an input file rather than only on the include path");
        assertFalse(Files.exists(lab.classes().resolve("dissaly/pb")),
                "the project compiled its own dissaly.pb, so a handler would be handed an Input "
                + "from the wrong classloader");
    }

    @Test
    @DisplayName("the schema is written out of the jar, because protoc cannot read one")
    void writtenOutOfTheJar() throws Exception {
        var log = new StringBuilder();
        assertNotNull(lab.compile(log::append), log.toString());
        assertTrue(Files.isRegularFile(root.resolve("build/dissaly/proto/dissaly/job.proto")),
                "nothing wrote the schema where protoc could open it as a file");
    }

    @Test
    @DisplayName("its descriptor is named for the import, not for where it happened to be compiled")
    void descriptorPathMatchesTheImport() {
        // The one thing an eye cannot check and a rebuild silently changes.
        // Generated against a deeper include root the descriptor would say
        // "job.proto", the import says "dissaly/job.proto", and protobuf would hold
        // them to be two different files declaring the same types.
        assertEquals("dissaly/job.proto",
                dissaly.pb.Input.getDescriptor().getFile().getName());
    }

    @Test
    @DisplayName("nothing lists it as a schema of the project's own")
    void notTheProjectsOwnSchema() {
        Lab.Code c = lab.code();
        var names = c.protos().stream().map(p -> p.getFileName().toString()).sorted().toList();
        assertEquals(java.util.List.of("frames.proto", "lab.proto"), names,
                "dissaly's schema was counted as one of the project's, so `dissaly check` would "
                + "report rpcs nobody wrote");
    }
}
