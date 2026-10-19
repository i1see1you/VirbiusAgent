package io.virbius.control.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.common.OperatorResolver;
import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.ChallengeApprovalRecord;
import io.virbius.control.repository.ChallengeApprovalRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/challenges")
public class ChallengeController {

    private static final Logger log = LoggerFactory.getLogger(ChallengeController.class);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChallengeApprovalRepository approvalRepo;

    @Value("${virbius.engine.base-url:http://127.0.0.1:8082}")
    private String engineBaseUrl;

    public ChallengeController(ChallengeApprovalRepository approvalRepo) {
        this.approvalRepo = approvalRepo;
    }

    @GetMapping
    public ResponseEntity<?> listChallenges(
            @RequestParam(name = "tenant_id", defaultValue = "default") String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int max) {
        try {
            // For approved / rejected, query from SQL
            if (status != null && !status.isBlank()
                    && !"pending".equals(status) && !"expired".equals(status)) {
                List<ChallengeApprovalRecord> records =
                        approvalRepo.listByTenantAndStatus(tenantId, status, max);
                List<Map> result = records.stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("challenge_id", r.challengeId());
                    m.put("tenant_id", r.tenantId());
                    m.put("status", r.status());
                    m.put("tool_name", r.toolName());
                    m.put("args_hash", r.argsHash());
                    m.put("session_id", r.sessionId());
                    m.put("rule_id", r.ruleId());
                    m.put("reason_code", r.reasonCode());
                    m.put("risk_score", r.riskScore());
                    m.put("approval_mode", r.approvalMode());
                    m.put("created_at", r.createdAt());
                    m.put("expires_at", r.expiresAt());
                    m.put("approved_by", r.approvedBy());
                    m.put("approved_at", r.approvedAt());
                    m.put("rejected_by", r.rejectedBy());
                    m.put("rejected_at", r.rejectedAt());
                    m.put("comment", r.comment());
                    return m;
                }).collect(Collectors.toList());
                return ResponseEntity.ok(result);
            }

            // For pending / expired / no status, proxy to engine
            URI uri = UriComponentsBuilder.fromUriString(engineBaseUrl)
                    .path("/v1/challenges")
                    .queryParam("tenantId", tenantId)
                    .queryParam("max", max)
                    .queryParam("status", status != null && !status.isBlank() ? status : null)
                    .build()
                    .encode()
                    .toUri();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                List<Map> challenges = mapper.readValue(resp.body(), List.class);
                return ResponseEntity.ok(challenges);
            }
            return errorResponse(resp.statusCode(), "engine returned HTTP " + resp.statusCode());
        } catch (Exception e) {
            log.error("failed to list challenges", e);
            return errorResponse(500, "challenge service unavailable");
        }
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<?> getStatus(@PathVariable String id) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(engineBaseUrl)
                    .pathSegment("v1", "challenge", id, "status")
                    .build()
                    .encode()
                    .toUri();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map result = mapper.readValue(resp.body(), Map.class);
                return ResponseEntity.ok(result);
            }
            return errorResponse(resp.statusCode(), "engine returned HTTP " + resp.statusCode());
        } catch (Exception e) {
            log.error("failed to get challenge status", e);
            return errorResponse(500, "challenge service unavailable");
        }
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(
            @PathVariable String id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        try {
            // Record a real actor: explicit body value wins, else X-User-Id /
            // authenticated principal, else an honest "unknown" — never "operator".
            String approvedBy = OperatorResolver.resolve(body.get("approved_by"), request, "unknown");
            // Forward the resolved identity so the engine payload and the SQL
            // approval record agree even when the caller left the field blank.
            Map<String, String> fwd = new LinkedHashMap<>(body);
            fwd.put("approved_by", approvedBy);

            // Fetch the current challenge record before approving
            Map record = fetchStatus(id);
            if (record == null) {
                return errorResponse(400, "challenge not found");
            }
            if (!"pending".equals(record.get("status"))) {
                return notPending(record);
            }

            URI uri = UriComponentsBuilder.fromUriString(engineBaseUrl)
                    .pathSegment("v1", "challenge", id, "approve")
                    .build()
                    .encode()
                    .toUri();
            String json = mapper.writeValueAsString(fwd);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("engine returned status {} for approve {}", resp.statusCode(), id);
                return errorResponse(resp.statusCode(), "engine returned HTTP " + resp.statusCode());
            }
            @SuppressWarnings("unchecked")
            Map result = mapper.readValue(resp.body(), Map.class);

            // Persist to SQL on success
            if (resp.statusCode() == 200 && result != null
                    && "approved".equals(result.get("status"))) {
                try {
                    String comment = body.get("comment");
                    long now = Instant.now().getEpochSecond();
                    approvalRepo.save(new ChallengeApprovalRecord(
                            id,
                            str(record.get("tenant_id")),
                            "approved",
                            str(record.get("tool_name")),
                            str(record.get("args_hash")),
                            str(record.get("session_id")),
                            str(record.get("rule_id")),
                            str(record.get("reason_code")),
                            intVal(record.get("risk_score")),
                            str(record.get("approval_mode")),
                            longVal(record.get("created_at")),
                            longVal(record.get("expires_at")),
                            approvedBy,
                            now,
                            null,
                            null,
                            comment));
                } catch (Exception e) {
                    log.error("failed to persist approved challenge {}: {}", id, e.getMessage());
                }
            }

            return ResponseEntity.status(resp.statusCode()).body(result);
        } catch (Exception e) {
            log.error("failed to approve challenge {}", id, e);
            return errorResponse(500, "challenge service unavailable");
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(
            @PathVariable String id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        try {
            // Same identity chain as approve: explicit value → request → "unknown".
            String rejectedBy = OperatorResolver.resolve(body.get("rejected_by"), request, "unknown");
            Map<String, String> fwd = new LinkedHashMap<>(body);
            fwd.put("rejected_by", rejectedBy);

            Map record = fetchStatus(id);
            if (record == null) {
                return errorResponse(400, "challenge not found");
            }
            if (!"pending".equals(record.get("status"))) {
                return notPending(record);
            }

            URI uri = UriComponentsBuilder.fromUriString(engineBaseUrl)
                    .pathSegment("v1", "challenge", id, "reject")
                    .build()
                    .encode()
                    .toUri();
            String json = mapper.writeValueAsString(fwd);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("engine returned status {} for reject {}", resp.statusCode(), id);
                return errorResponse(resp.statusCode(), "engine returned HTTP " + resp.statusCode());
            }
            @SuppressWarnings("unchecked")
            Map result = mapper.readValue(resp.body(), Map.class);

            if (resp.statusCode() == 200 && result != null
                    && "rejected".equals(result.get("status"))) {
                try {
                    String reason = body.get("reason");
                    long now = Instant.now().getEpochSecond();
                    approvalRepo.save(new ChallengeApprovalRecord(
                            id,
                            str(record.get("tenant_id")),
                            "rejected",
                            str(record.get("tool_name")),
                            str(record.get("args_hash")),
                            str(record.get("session_id")),
                            str(record.get("rule_id")),
                            str(record.get("reason_code")),
                            intVal(record.get("risk_score")),
                            str(record.get("approval_mode")),
                            longVal(record.get("created_at")),
                            longVal(record.get("expires_at")),
                            null,
                            null,
                            rejectedBy,
                            now,
                            reason));
                } catch (Exception e) {
                    log.error("failed to persist rejected challenge {}: {}", id, e.getMessage());
                }
            }

            return ResponseEntity.status(resp.statusCode()).body(result);
        } catch (Exception e) {
            log.error("failed to reject challenge {}", id, e);
            return errorResponse(500, "challenge service unavailable");
        }
    }

    // -- helpers --

    /**
     * Fetches the challenge status. Returns {@code null} only when the engine answers
     * 404 (the challenge genuinely does not exist); any other non-200 response throws,
     * so approve/reject surface a 500 instead of a misleading 400 "challenge not found"
     * while the engine is unhealthy.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchStatus(String challengeId) throws Exception {
        URI uri = UriComponentsBuilder.fromUriString(engineBaseUrl)
                .pathSegment("v1", "challenge", challengeId, "status")
                .build()
                .encode()
                .toUri();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
            return mapper.readValue(resp.body(), Map.class);
        }
        if (resp.statusCode() == 404) {
            return null;
        }
        throw new IllegalStateException("engine returned HTTP " + resp.statusCode());
    }

    /**
     * Error bodies use the same {@code ApiResult} envelope ({@code code}/{@code message}) as
     * every other control route, so callers parse one shape. The body is never empty — an
     * empty body is what broke {@code res.json()} on the console.
     */
    private ResponseEntity<ApiResult<Void>> errorResponse(int status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResult.error(status, message));
    }

    private ResponseEntity<ApiResult<Map<String, String>>> notPending(Map<String, Object> record) {
        return ResponseEntity.status(400)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResult.error(400, "challenge is not pending",
                        Map.of("current_status", String.valueOf(record.get("status")))));
    }

    private static String str(Object v) {
        return v != null ? v.toString() : null;
    }

    private static long longVal(Object v) {
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    private static int intVal(Object v) {
        if (v instanceof Number n) return n.intValue();
        return 0;
    }
}
