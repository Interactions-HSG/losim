package losim.gen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * .proto -> Java. Messages become records; each service becomes two interfaces.
 *
 * The split is deliberate. The SERVER interface takes a Ctx, because the callee
 * needs its own context. The PEER interface does not, because a caller has no
 * business supplying it. Implementing the server interface is what makes the
 * cross-machine contract a compile error rather than a runtime surprise.
 */
public final class ProtoGen {

    record Field(String type, String name, boolean repeated, String mapKey, String mapValue) {}
    record Message(String name, List<Field> fields) {}
    record Rpc(String name, String request, String response) {}
    record Service(String name, List<Rpc> rpcs) {}

    private final List<Message> messages = new ArrayList<>();
    private final List<Service> services = new ArrayList<>();

    public static void generate(Path protoFile, Path outDir, String pkg) throws IOException {
        ProtoGen g = new ProtoGen();
        g.parse(Files.readString(protoFile));
        Files.createDirectories(outDir);
        for (Message m : g.messages) Files.writeString(outDir.resolve(m.name() + ".java"), g.record(m, pkg));
        for (Service s : g.services) {
            Files.writeString(outDir.resolve(s.name() + "Service.java"), g.server(s, pkg));
            Files.writeString(outDir.resolve(s.name() + "Peer.java"), g.peer(s, pkg));
        }
    }

    // ------------------------------------------------------------------ parse

    void parse(String text) {
        String src = text.replaceAll("//[^\n]*", " ");
        Matcher msg = Pattern.compile("message\\s+(\\w+)\\s*\\{([^}]*)}", Pattern.DOTALL).matcher(src);
        while (msg.find()) messages.add(new Message(msg.group(1), fields(msg.group(2))));

        Matcher svc = Pattern.compile("service\\s+(\\w+)\\s*\\{([^}]*)}", Pattern.DOTALL).matcher(src);
        while (svc.find()) {
            List<Rpc> rpcs = new ArrayList<>();
            Matcher r = Pattern.compile("rpc\\s+(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)\\s*returns\\s*\\(\\s*(\\w+)\\s*\\)")
                    .matcher(svc.group(2));
            while (r.find()) rpcs.add(new Rpc(r.group(1), r.group(2), r.group(3)));
            services.add(new Service(svc.group(1), rpcs));
        }
    }

    static List<Field> fields(String body) {
        List<Field> out = new ArrayList<>();
        Matcher mapField = Pattern.compile("map\\s*<\\s*(\\w+)\\s*,\\s*(\\w+)\\s*>\\s+(\\w+)\\s*=\\s*\\d+").matcher(body);
        while (mapField.find())
            out.add(new Field(null, mapField.group(3), false, mapField.group(1), mapField.group(2)));

        Matcher plain = Pattern.compile("(repeated\\s+)?(\\w+)\\s+(\\w+)\\s*=\\s*\\d+").matcher(body);
        while (plain.find()) {
            if (plain.group(2).equals("map")) continue;
            String name = plain.group(3);
            boolean already = out.stream().anyMatch(f -> f.name().equals(name));
            if (already) continue;
            out.add(new Field(plain.group(2), name, plain.group(1) != null, null, null));
        }
        return out;
    }

    // ------------------------------------------------------------------ emit

    static String javaType(Field f) {
        if (f.mapKey() != null) return "java.util.Map<" + boxed(f.mapKey()) + ", " + boxed(f.mapValue()) + ">";
        String base = scalar(f.type());
        return f.repeated() ? "java.util.List<" + boxed(f.type()) + ">" : base;
    }

    static String scalar(String t) {
        return switch (t) {
            case "string" -> "String";
            case "int32", "uint32", "sint32" -> "int";
            case "int64", "uint64", "sint64" -> "long";
            case "bool" -> "boolean";
            case "double" -> "double";
            case "float" -> "float";
            case "bytes" -> "byte[]";
            case "Data" -> "losim.api.Data";     // a described dataset, not a materialised one
            case "DataRef" -> "losim.api.DataRef";
            default -> t;
        };
    }

    static String boxed(String t) {
        return switch (t) {
            case "string" -> "String";
            case "int32", "uint32", "sint32" -> "Integer";
            case "int64", "uint64", "sint64" -> "Long";
            case "bool" -> "Boolean";
            case "double" -> "Double";
            case "float" -> "Float";
            case "bytes" -> "byte[]";
            case "Data" -> "losim.api.Data";
            case "DataRef" -> "losim.api.DataRef";
            default -> t;
        };
    }

    static String lower(String s) { return Character.toLowerCase(s.charAt(0)) + s.substring(1); }

    String record(Message m, String pkg) {
        StringBuilder sb = new StringBuilder();
        header(sb, pkg);
        sb.append("/** Generated from the .proto. Do not edit — edit the schema. */\n");
        sb.append("public record ").append(m.name()).append("(");
        for (int i = 0; i < m.fields().size(); i++) {
            Field f = m.fields().get(i);
            if (i > 0) sb.append(", ");
            sb.append(javaType(f)).append(' ').append(f.name());
        }
        sb.append(") {}\n");
        return sb.toString();
    }

    String server(Service s, String pkg) {
        StringBuilder sb = new StringBuilder();
        header(sb, pkg);
        sb.append("import losim.api.Ctx;\n\n");
        sb.append("/**\n * The SERVER side of ").append(s.name()).append(".\n")
          .append(" * Implement this and javac enforces the contract across machines.\n */\n");
        sb.append("public interface ").append(s.name()).append("Service {\n");
        for (Rpc r : s.rpcs())
            sb.append("    ").append(r.response()).append(' ').append(lower(r.name()))
              .append("(Ctx ctx, ").append(r.request()).append(" request);\n");
        sb.append("}\n");
        return sb.toString();
    }

    String peer(Service s, String pkg) {
        StringBuilder sb = new StringBuilder();
        header(sb, pkg);
        sb.append("import losim.api.Peer;\nimport losim.api.ServiceOf;\n\n");
        sb.append("/**\n * The CLIENT side of ").append(s.name()).append(".\n")
          .append(" * No Ctx: a caller has no business supplying the callee's context.\n */\n");
        sb.append("@ServiceOf(").append(s.name()).append("Service.class)\n");
        sb.append("public interface ").append(s.name()).append("Peer extends Peer {\n");
        for (Rpc r : s.rpcs())
            sb.append("    ").append(r.response()).append(' ').append(lower(r.name()))
              .append('(').append(r.request()).append(" request);\n");
        sb.append("}\n");
        return sb.toString();
    }

    static void header(StringBuilder sb, String pkg) {
        if (pkg != null && !pkg.isBlank()) sb.append("package ").append(pkg).append(";\n\n");
    }
}
