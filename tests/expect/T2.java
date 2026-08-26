import java.util.List;
import java.util.Map;

/**
 * t2-one-call — one client, one server, one unary call, over real gRPC.
 *
 * <p><b>Catches:</b> codegen and marshalling wiring; the {@code Mapper/Map} trap;
 * and a payload renderer that drifts, which would make two traces of the same run
 * fail to diff.
 *
 * <p>Read out of the trace on disk rather than out of losim's own objects, because
 * the trace is the interchange format and everything downstream sees only this.
 */
public final class T2 {
    public static void main(String[] args) {
        var e = Expect.of("t2-one-call", args);

        var calls = e.of("rpc_call");
        var starts = e.of("handler_start");
        var ends = e.of("handler_end");
        e.check(calls.size() == 2 && starts.size() == 2 && ends.size() == 2,
                "two calls, each one rpc_call -> handler_start -> handler_end (" + calls.size()
                + "/" + starts.size() + "/" + ends.size() + ")");

        var mapCall = calls.stream()
                .filter(c -> String.valueOf(Expect.detail(c).get("method")).endsWith(".Map"))
                .findFirst().orElse(Map.of());
        String method = String.valueOf(Expect.detail(mapCall).get("method"));
        e.check(method.equals("lab.Worker.Map"),
                "method is dotted — " + method + ", not lab.Worker/Map, which every view "
                + "downstream splits on");

        // The marshaller's own number, recomputed here from the message the job sent.
        long expected = OneCall.request().getSerializedSize() + 5;
        var handler = e.spansOf("handler").stream()
                .filter(s -> String.valueOf(s.get("label")).endsWith(".Map")).findFirst().orElse(Map.of());
        long counted = Expect.lng(Expect.detail(handler).get("inBytes"));
        e.check(counted == expected,
                "counted bytes are getSerializedSize() plus framing (" + counted + " = "
                + expected + ") — the in-process transport hands over a reference and "
                + "serializes nothing, so a byte count taken from it would be zero");

        // The D9 trap: a renderer that walks fields in declaration order, or leaves a
        // map unsorted, produces a trace that will not diff against its own rerun.
        String rendered = String.valueOf(Expect.detail(handler).get("result"));
        e.note("rendered: " + rendered);
        int a = rendered.indexOf("cat"), t = rendered.indexOf("the");
        e.check(a >= 0 && t >= 0 && a < t,
                "map entries are rendered sorted, so two traces of the same run diff empty");

        var noteSpan = e.spansOf("handler").stream()
                .filter(s -> String.valueOf(s.get("label")).endsWith(".Note")).findFirst().orElse(Map.of());
        String note = String.valueOf(Expect.detail(noteSpan).get("arg"));
        e.note("rendered: " + note);
        e.check(note.contains("WARN") && note.contains("42"),
                "an enum renders by name and a oneof by the arm that is set — a schema with "
                + "either in it is where a renderer stops being obvious");

        var done = e.of("done");
        e.check(!done.isEmpty() && String.valueOf(Expect.detail(done.get(0)).get("value")).contains("cat=1"),
                "and the client got back what the handler returned");

        e.check(Boolean.TRUE.equals(e.meta().get("completed"))
                && List.of("FULL", "NO_PAYLOAD").contains(String.valueOf(e.meta().get("telemetry"))),
                "the trace says what it is: completed, and at which telemetry level");
        e.done();
    }
}
