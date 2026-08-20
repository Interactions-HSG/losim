package losim.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/** Non-functional counters, gathered as the run proceeds. */
public final class Metrics {
    public long messages;
    public long bytes;
    public long crossZoneBytes;
    public long rpcCalls;
    public long rpcTimeouts;
    public long rpcDropped;
    public long duplicateWork;
    public long kills;
    public final Map<String, Long> busyMsByVm = new LinkedHashMap<>();

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("messages", messages);
        m.put("bytes", bytes);
        m.put("crossZoneBytes", crossZoneBytes);
        m.put("rpcCalls", rpcCalls);
        m.put("rpcTimeouts", rpcTimeouts);
        m.put("rpcDropped", rpcDropped);
        m.put("duplicateWork", duplicateWork);
        m.put("kills", kills);
        m.put("busyMsByVm", busyMsByVm);
        return m;
    }
}
