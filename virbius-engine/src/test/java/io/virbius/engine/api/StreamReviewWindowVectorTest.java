package io.virbius.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import org.junit.jupiter.api.Test;

/**
 * Replays the shared window vectors from
 * {@code schemas/review-window-vectors.json} — the single source of truth also
 * consumed by the Python SDK tests, so the engine and the SDK can never drift
 * apart on the sliding-window algorithm.
 */
class StreamReviewWindowVectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void replaysSharedVectors() throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(locateVectors()));
        assertTrue(root.path("cases").isArray(), "vectors file must contain cases[]");
        int replayed = 0;
        for (Iterator<JsonNode> it = root.path("cases").elements(); it.hasNext(); ) {
            replayCase(it.next());
            replayed++;
        }
        assertTrue(replayed >= 5, "expected at least 5 vector cases, got " + replayed);
    }

    private void replayCase(JsonNode c) {
        String name = c.path("name").asText("<unnamed>");
        StreamReviewWindow window = new StreamReviewWindow(
                c.path("windowChars").asInt(), c.path("contextChars").asInt());
        for (Iterator<JsonNode> it = c.path("steps").elements(); it.hasNext(); ) {
            JsonNode step = it.next();
            String due = window.due(
                    step.path("delta").asText(""),
                    step.path("done").asBoolean(false));
            JsonNode expectedDue = step.get("due");
            if (expectedDue == null || expectedDue.isNull()) {
                assertEquals(null, due, name + ": due should not be ready");
            } else {
                assertEquals(expectedDue.asText(), due, name + ": due text mismatch");
            }
            String then = step.path("then").asText("");
            if ("accept".equals(then)) {
                assertEquals(step.path("forward").asText(), window.accept(),
                        name + ": forwarded text mismatch");
                assertEquals(step.path("tail").asText(""), window.reviewedTail(),
                        name + ": context tail mismatch");
            } else if ("reject".equals(then)) {
                window.reject();
                assertEquals(0, window.pendingLength(), name + ": reject must drop pending");
            }
        }
    }

    /** Locate the repo-level vectors file from the module working directory. */
    static Path locateVectors() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve("schemas").resolve("review-window-vectors.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        org.junit.jupiter.api.Assumptions.abort(
                "schemas/review-window-vectors.json not found from " + Path.of("").toAbsolutePath());
        return null;
    }
}
