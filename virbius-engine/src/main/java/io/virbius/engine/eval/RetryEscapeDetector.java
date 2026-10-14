package io.virbius.engine.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * C2/C3: retry-escape detection with a wash-out guard.
 *
 * <p>After a tool call is blocked/challenged (recorded with {@code allowed=false}
 * in the session history), a determined agent retries by changing the tool,
 * arguments or path. Conservative trigger (avoid false positives — the outline
 * checks normal tasks too): the current call is at least the 3rd attempt after
 * the last block, within the window, and at least one attempt changed tool or
 * args. C3 wash-out: if ≥3 consecutive calls after the block were allowed, the
 * session is considered to have moved on — no escape is raised.
 *
 * <p>Emission is {@code dry_run} unless {@code virbius.retry-escape.enforce=true}.
 */
@Component
public class RetryEscapeDetector {

    private static final Logger log = LoggerFactory.getLogger(RetryEscapeDetector.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enforce;

    private static final int WINDOW = 10;
    private static final int MIN_ATTEMPTS_AFTER_BLOCK = 3;
    private static final int WASHOUT_ALLOWED = 3;

    public RetryEscapeDetector(
            @Value("${virbius.retry-escape.enforce:false}") boolean enforce) {
        this.enforce = enforce;
    }

    /**
     * @param history session tool history, most recent first
     * @return RETRY_ESCAPE signal when a post-block retry pattern is detected
     */
    public Optional<SignalDto> detect(List<Map<String, Object>> history, String toolName, String argsJson) {
        if (history == null || history.isEmpty()) {
            return Optional.empty();
        }
        // Ordered oldest → newest, bounded to the window.
        List<Attempt> attempts = new ArrayList<>();
        int size = history.size();
        for (int i = size - 1; i >= 0; i--) {
            attempts.add(toAttempt(history.get(i)));
        }
        if (attempts.size() > WINDOW) {
            attempts = attempts.subList(attempts.size() - WINDOW, attempts.size());
        }

        int lastBlock = -1;
        for (int i = 0; i < attempts.size(); i++) {
            if (!attempts.get(i).allowed()) {
                lastBlock = i;
            }
        }
        if (lastBlock < 0) {
            return Optional.empty();
        }
        Attempt blocked = attempts.get(lastBlock);

        List<Attempt> after = new ArrayList<>(attempts.subList(lastBlock + 1, attempts.size()));
        // C3 wash-out: enough consecutive allowed calls after the block means the
        // session moved on legitimately.
        int trailingAllowed = 0;
        for (int i = attempts.size() - 1; i > lastBlock; i--) {
            if (attempts.get(i).allowed()) {
                trailingAllowed++;
            } else {
                break;
            }
        }
        if (trailingAllowed >= WASHOUT_ALLOWED) {
            return Optional.empty();
        }

        // Include the current (not yet recorded) call as the newest attempt.
        Attempt current = new Attempt(toolName, argsHash(argsJson), false);
        after.add(current);
        if (after.size() < MIN_ATTEMPTS_AFTER_BLOCK) {
            return Optional.empty();
        }

        boolean changed = after.stream().anyMatch(a ->
                (a.tool() != null && !a.tool().equals(blocked.tool()))
                        || (a.argsHash() != null && !a.argsHash().equals(blocked.argsHash())));
        if (!changed) {
            return Optional.empty();
        }

        String mode = enforce ? "full" : "dry_run";
        log.info("retry escape detected ({}): session attempts_after_block={} blocked_tool={} current_tool={}",
                mode, after.size(), blocked.tool(), toolName);
        return Optional.of(new SignalDto(
                "retry-escape", 1, "cloud", "cloud", 30,
                "RETRY_ESCAPE", "challenge", mode, null, null));
    }

    private record Attempt(String tool, String argsHash, boolean allowed) {}

    private Attempt toAttempt(Map<String, Object> entry) {
        String tool = entry.get("tool_name") != null ? entry.get("tool_name").toString() : null;
        String args = entry.get("args") != null ? entry.get("args").toString() : "";
        boolean allowed = !Boolean.FALSE.equals(entry.get("allowed"));
        return new Attempt(tool, argsHash(args), allowed);
    }

    private String argsHash(String argsJson) {
        try {
            Map<String, Object> m = (argsJson == null || argsJson.isBlank())
                    ? Map.of()
                    : mapper.readValue(argsJson, new TypeReference<Map<String, Object>>() {});
            return Integer.toHexString(m.hashCode());
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(argsJson).hashCode());
        }
    }
}
