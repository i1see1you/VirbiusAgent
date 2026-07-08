package io.virbius.control.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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

/**
 * Challenge approval queue API for the Control dashboard.
 *
 * <p>Proxies challenge management requests to the Engine's /v1/challenge/* endpoints.
 * The dashboard polls {@code GET /api/v1/challenges} to display the pending approval queue,
 * and calls {@code POST /api/v1/challenges/{id}/approve|reject} to act on challenges.
 */
@RestController
@RequestMapping("/api/v1/challenges")
public class ChallengeController {

    private static final Logger log = LoggerFactory.getLogger(ChallengeController.class);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${virbius.engine.base-url:http://127.0.0.1:8082}")
    private String engineBaseUrl;

    /**
     * List challenges for the approval queue.
     *
     * @param tenantId tenant ID (defaults to "default")
     * @param status filter by status ("pending", "approved", "rejected", "expired")
     * @param max max results (default 50)
     * @return list of challenge records
     */
    @GetMapping
    public ResponseEntity<List<Map>> listChallenges(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int max) {
        try {
            String url = String.format("%s/v1/challenges?tenant_id=%s&max=%d",
                    engineBaseUrl, tenantId, max);
            if (status != null && !status.isBlank()) {
                url += "&status=" + status;
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
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

    /**
     * Get the status of a specific challenge.
     *
     * @param id challenge ID
     * @return challenge status record
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<Map> getStatus(@PathVariable String id) {
        try {
            String url = String.format("%s/v1/challenge/%s/status", engineBaseUrl, id);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
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

    /**
     * Approve a pending challenge.
     *
     * @param id challenge ID
     * @param body request body with {@code approved_by} and optional {@code comment}
     * @return approval result with one-time-use token
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Map> approve(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        try {
            String url = String.format("%s/v1/challenge/%s/approve", engineBaseUrl, id);
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map result = mapper.readValue(resp.body(), Map.class);
            return ResponseEntity.status(resp.statusCode()).body(result);
        } catch (Exception e) {
            log.error("failed to approve challenge: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Reject a pending challenge.
     *
     * @param id challenge ID
     * @param body request body with {@code rejected_by} and {@code reason}
     * @return rejection result
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<Map> reject(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        try {
            String url = String.format("%s/v1/challenge/%s/reject", engineBaseUrl, id);
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map result = mapper.readValue(resp.body(), Map.class);
            return ResponseEntity.status(resp.statusCode()).body(result);
        } catch (Exception e) {
            log.error("failed to reject challenge: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
