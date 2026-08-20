package losim.api;

import java.util.List;
import java.util.Map;

/** What a completed run produced. Handed to invariants and graders. */
public interface RunResult {
    Object output();
    Object input();
    long endedAtMs();
    Map<String, Object> metrics();
    List<Map<String, Object>> events();
    /** Events of one kind, oldest first. */
    List<Map<String, Object>> events(String kind);
    boolean finished();
    /** The five-bucket bill for this run. */
    Map<String, Object> bill();
}
