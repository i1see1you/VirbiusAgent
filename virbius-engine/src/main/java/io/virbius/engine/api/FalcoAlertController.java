package io.virbius.engine.api;

import io.virbius.engine.eval.SessionRiskManager;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API for receiving asynchronous Falco alerts from the edge/kernel layer.
 *
 * <p>Falco alerts are sent via HTTP POST from the kernel plugin (after pidmap resolves
 * the host PID to a session ID). The alert increments a pending counter in Redis,
 * which is consumed by the next {@code SessionRiskManager.updateRiskScore()} call.
 *
 * <p>Endpoint: {@code POST /api/internal/falco-alert}
 */
@RestController
@RequestMapping("/api/internal")
public class FalcoAlertController {

    private static final Logger log = LoggerFactory.getLogger(FalcoAlertController.class);

    private final SessionRiskManager riskManager;

    public FalcoAlertController(SessionRiskManager riskManager) {
        this.riskManager = riskManager;
    }

    @PostMapping("/falco-alert")
    public Map<String, Object> onFalcoAlert(@RequestBody FalcoAlertEvent event) {
        if (event.sessionId() != null && !event.sessionId().isBlank()) {
            riskManager.onFalcoAlert(event.sessionId());
            log.info("falco alert received: session={} rule={} priority={}",
                    event.sessionId(), event.rule(), event.priority());
        } else {
            log.debug("falco alert received without session_id, ignoring");
        }
        return Map.of("status", "ok");
    }

    /**
     * Falco alert event payload.
     *
     * @param sessionId the Agent session ID (resolved by pidmap)
     * @param rule      the Falco rule name that triggered
     * @param priority  the alert priority (emergency, alert, critical, etc.)
     * @param output    the formatted alert message
     */
    public record FalcoAlertEvent(
            String sessionId,
            String rule,
            String priority,
            String output) {}
}
