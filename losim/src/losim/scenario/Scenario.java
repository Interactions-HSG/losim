package losim.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The instructor's dial-turning surface, as data. */
public final class Scenario {

    public String name = "scenario";
    public long seed = 1;
    public long runUntilMs = 30_000;
    public String codec = "proto";
    public String input = "";
    public String prices = "";

    public final List<VmGroup> groups = new ArrayList<>();
    public final NetworkSpec network = new NetworkSpec();
    public final List<FaultSpec> faults = new ArrayList<>();
    public final List<InvariantSpec> invariants = new ArrayList<>();
    public Sweep sweep;

    public static final class VmGroup {
        public String key;
        public String prefix;
        public List<String> programs = new ArrayList<>();
        public String instance = "m5.large";
        public List<String> zones = new ArrayList<>(List.of("eu-central-1a"));
        public String market = "on-demand";
        public int count = 1;
        public final Map<String, Map<String, Node>> overrides = new LinkedHashMap<>();
        public boolean named;                       // an individually named VM, not a pool
    }

    public static final class NetworkSpec {
        public String topology = "mesh";
        public double meanMs = 20, stddevMs = 5, loss = 0;
        public double crossZoneFactor = 3.0;
    }

    public static final class FaultSpec {
        public long atMs;
        public String kind;                          // kill | freeze | degrade | spot_reclaim | ...
        public String target;
        public double cpu = 1.0;
        public long durationMs;
        public long noticeMs;
        public long restartAfterMs = -1;
        public List<List<String>> groups = new ArrayList<>();
    }

    public static final class InvariantSpec {
        public String name;
        public String check;
        public final Map<String, String> args = new LinkedHashMap<>();
    }

    public static final class Sweep {
        public List<Long> seeds = new ArrayList<>();
        public Map<String, List<String>> matrix = new LinkedHashMap<>();
    }
}
