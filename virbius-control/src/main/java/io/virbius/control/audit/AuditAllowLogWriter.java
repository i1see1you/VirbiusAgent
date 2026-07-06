package io.virbius.control.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Append-only JSONL for allow audit events (not stored in tb_audit_events).
 *
 * <p>Writes go through a dedicated logback logger ("virbius.audit.allow") so that
 * rolling/retention/compression are handled by the logback rolling policy
 * (see logback-spring.xml). Each line is a single JSON object.
 */
@Component
public class AuditAllowLogWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditAllowLogWriter.class);
    private static final Logger allowLog = LoggerFactory.getLogger("virbius.audit.allow");

    private final ObjectMapper mapper = new ObjectMapper();

    public void append(Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return;
        }
        try {
            allowLog.info(mapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("audit allow log write failed: {}", e.getMessage());
        }
    }

    public void appendJson(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        allowLog.info(json.strip());
    }

    public static boolean isAllowAction(Map<String, Object> event) {
        if (event == null) {
            return false;
        }
        return "allow".equalsIgnoreCase(String.valueOf(event.get("effective_action")));
    }
}
