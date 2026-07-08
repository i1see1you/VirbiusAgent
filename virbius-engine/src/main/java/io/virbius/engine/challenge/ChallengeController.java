package io.virbius.engine.challenge;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for challenge lifecycle management.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /v1/challenge/{id}/status} — query challenge status (for proxy polling)</li>
 *   <li>{@code POST /v1/challenge/{id}/approve} — approve a pending challenge (dashboard)</li>
 *   <li>{@code POST /v1/challenge/{id}/reject} — reject a pending challenge (dashboard)</li>
 *   <li>{@code POST /v1/challenge/verify} — verify a challenge token (gateway/proxy)</li>
 *   <li>{@code GET  /v1/challenges} — list challenges for dashboard queue</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1")
public class ChallengeController {

    private static final Logger log = LoggerFactory.getLogger(ChallengeController.class);
    private final ChallengeService challengeService;

    public ChallengeController(ChallengeService challengeService) {
        this.challengeService = challengeService;
    }

    /**
     * Query the status of a challenge.
     *
     * <p>Used by MCP Proxy to poll for inline approval, or by the Agent SDK
     * to check if a dashboard approval has completed.
     *
     * @param id challenge ID
     * @return challenge status record
     */
    @GetMapping("/challenge/{id}/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String id) {
        Map<String, Object> result = challengeService.getStatus(id);
        if ("not_found".equals(result.get("status"))) {
            return ResponseEntity.status(404).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Approve a pending challenge.
     *
     * <p>Called from the Control dashboard by an operator.
     *
     * @param id challenge ID
     * @param body request body containing {@code approved_by} and optional {@code comment}
     * @return approval result with one-time-use token
     */
    @PostMapping("/challenge/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String approvedBy = body.getOrDefault("approved_by", "unknown");
        String comment = body.getOrDefault("comment", "");
        log.info("approve request: challenge={} by={}", id, approvedBy);
        Map<String, Object> result = challengeService.approve(id, approvedBy, comment);
        String status = String.valueOf(result.get("status"));
        if ("not_found".equals(status)) {
            return ResponseEntity.status(404).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Reject a pending challenge.
     *
     * @param id challenge ID
     * @param body request body containing {@code rejected_by} and {@code reason}
     * @return rejection result
     */
    @PostMapping("/challenge/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String rejectedBy = body.getOrDefault("rejected_by", "unknown");
        String reason = body.getOrDefault("reason", "");
        log.info("reject request: challenge={} by={}", id, rejectedBy);
        Map<String, Object> result = challengeService.reject(id, rejectedBy, reason);
        String status = String.valueOf(result.get("status"));
        if ("not_found".equals(status)) {
            return ResponseEntity.status(404).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Verify a challenge token.
     *
     * <p>Called by the MCP Proxy or Gateway when a tool call is retried with
     * a {@code X-Virbius-Challenge-Token} header. The token is one-time-use.
     *
     * @param body request body containing {@code token}, {@code tool_name},
     *             {@code args_hash}, and {@code session_id}
     * @return verification result
     */
    @PostMapping("/challenge/verify")
    public ResponseEntity<Map<String, Object>> verifyToken(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String toolName = body.get("tool_name");
        String argsHash = body.get("args_hash");
        String sessionId = body.get("session_id");
        Map<String, Object> result = challengeService.verifyToken(token, toolName, argsHash, sessionId);
        boolean valid = Boolean.TRUE.equals(result.get("valid"));
        if (!valid) {
            return ResponseEntity.status(403).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * List challenges for the dashboard approval queue.
     *
     * @param tenantId tenant ID (defaults to "default")
     * @param status filter by status ("pending", "approved", "rejected", "expired")
     * @param max max results (default 50)
     * @return list of challenge records
     */
    @GetMapping("/challenges")
    public ResponseEntity<List<Map<String, Object>>> listChallenges(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int max) {
        List<Map<String, Object>> challenges = challengeService.listChallenges(tenantId, status, max);
        return ResponseEntity.ok(challenges);
    }
}
