package io.virbius.control.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.domain.ChallengeApprovalRecord;
import io.virbius.control.repository.ChallengeApprovalRepository;
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
    public ResponseEntity<List<Map>> listChallenges(
            @RequestParam(defaultValue = "default") String tenantId,
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
                    .queryParam("tenant_id", tenantId)
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
            return ResponseEntity.status(resp.statusCode()).build();
        } catch (Exception e) {
            log.error("failed to list challenges: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Map> getStatus(@PathVariable String id) {
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
            return ResponseEntity.status(resp.statusCode()).build();
        } catch (Exception e) {
            log.error("failed to get challenge status: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Map> approve(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        try {
            // Fetch the current challenge record before approving
            Map record = fetchStatus(id);
            if (record == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "challenge not found"));
            }
            if (!"pending".equals(record.get("status"))) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "challenge is not pending",
                                "current_status", record.get("status")));
            }

            URI uri = UriComponentsBuilder.fromUriString(engineBaseUrl)
                    .pathSegment("v1", "challenge", id, "approve")
                    .build()
                    .encode()
                    .toUri();
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map result = mapper.readValue(resp.body(), Map.class);

            // Persist to SQL on success
            if (resp.statusCode() == 200 && result != null
                    && "approved".equals(result.get("status"))) {
                try {
                    String approvedBy = body.get("approved_by");
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
            log.error("failed to approve challenge: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Map> reject(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        try {
            Map record = fetchStatus(id);
            if (record == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "challenge not found"));
            }
            if (!"pending".equals(record.get("status"))) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "challenge is not pending",
                                "current_status", record.get("status")));
            }

            URI uri = UriComponentsBuilder.fromUriString(engineBaseUrl)
                    .pathSegment("v1", "challenge", id, "reject")
                    .build()
                    .encode()
                    .toUri();
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map result = mapper.readValue(resp.body(), Map.class);

            if (resp.statusCode() == 200 && result != null
                    && "rejected".equals(result.get("status"))) {
                try {
                    String rejectedBy = body.get("rejected_by");
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
            log.error("failed to reject challenge: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // -- helpers --

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchStatus(String challengeId) {
        try {
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
            return null;
        } catch (Exception e) {
            log.warn("failed to fetch challenge status {}: {}", challengeId, e.getMessage());
            return null;
        }
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
