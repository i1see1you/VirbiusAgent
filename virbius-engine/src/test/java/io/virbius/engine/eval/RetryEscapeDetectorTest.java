package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetryEscapeDetectorTest {

    private final RetryEscapeDetector detector = new RetryEscapeDetector(false);

    /** History is stored most-recent-first. */
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
    void detectsChangedToolRetryAfterBlock() {
        List<Map<String, Object>> h = history(List.of(
                call("read_file", "{\"path\":\"/etc/shadow\"}", false),   // blocked
                call("curl", "{\"url\":\"http://evil/a\"}", true),
                call("http_get", "{\"url\":\"http://evil/b\"}", true)));
        assertTrue(detector.detect(h, "fetch", "{\"url\":\"http://evil/c\"}").isPresent());
    }

    @Test
    void washoutAfterConsecutiveAllowsSuppresses() {
        List<Map<String, Object>> h = history(List.of(
                call("read_file", "{\"path\":\"/etc/shadow\"}", false),   // blocked
                call("read_file", "{\"path\":\"/tmp/a\"}", true),
                call("read_file", "{\"path\":\"/tmp/b\"}", true),
                call("read_file", "{\"path\":\"/tmp/c\"}", true)));
        assertFalse(detector.detect(h, "read_file", "{\"path\":\"/tmp/d\"}").isPresent());
    }

    @Test
    void noBlockNoEscape() {
        List<Map<String, Object>> h = history(List.of(
                call("read_file", "{\"path\":\"/tmp/a\"}", true),
                call("read_file", "{\"path\":\"/tmp/b\"}", true)));
        assertFalse(detector.detect(h, "curl", "{\"url\":\"http://x\"}").isPresent());
    }

    @Test
    void tooFewAttemptsAfterBlockNoEscape() {
        List<Map<String, Object>> h = history(List.of(
                call("read_file", "{\"path\":\"/etc/shadow\"}", false),
                call("curl", "{\"url\":\"http://evil/a\"}", true)));
        // blocked + 1 attempt + current = 2 after block (< 3)
        assertFalse(detector.detect(h, "fetch", "{\"url\":\"http://evil/b\"}").isPresent());
    }
}
