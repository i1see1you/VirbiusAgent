package io.virbius.engine.api;

import io.virbius.engine.eval.EvaluateOrchestrator;
import io.virbius.engine.eval.EvaluateRequestDto;
import io.virbius.engine.eval.EvaluateResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authoritative internal decision API (HTTP/JSON). This is the single decision
 * path consumed by the MCP proxy, the gateway (compiled {@code engine_url}),
 * and the Control-side simulate/challenge facades. The former gRPC mirror
 * (port 50051) was removed — see CHANGELOG.
 *
 * <p><b>Review roles</b> (see PROTOCOL "evaluate role side-effect matrix"):
 * <ul>
 *   <li>{@code role=input} — content review of a user message before it reaches
 *       the model. Feeds session risk (enforced hits only, once per trace);
 *       never creates challenges, counters or tool-call history.</li>
 *   <li>{@code role=output} — content review of a model answer, routed through
 *       {@link OutputReviewService}: zero side effects and the response carries
 *       {@code disposal_action} (+ {@code sanitized_content} for
 *       {@code redact}) which callers apply verbatim.</li>
 * </ul>
 *
 * <p>{@code role} is a closed enum: {@code input | output | tool_call}. Blank
 * (or absent) defaults to {@code tool_call} semantics; any other value —
 * including the legacy {@code user} — is rejected with HTTP 400
 * ({@code unsupported_role}) so a mis-integrated caller fails loudly instead
 * of silently running an undocumented role.
 */
@RestController
public class EvaluateHttpController {

    private static final java.util.Set<String> SUPPORTED_ROLES =
            java.util.Set.of("input", "output", "tool_call");

    private final EvaluateOrchestrator orchestrator;
    private final OutputReviewService outputReview;
    private final EvaluateRateLimiter rateLimiter;

    public EvaluateHttpController(
            EvaluateOrchestrator orchestrator,
            OutputReviewService outputReview,
            EvaluateRateLimiter rateLimiter) {
        this.orchestrator = orchestrator;
        this.outputReview = outputReview;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/v1/evaluate")
    public ResponseEntity<?> evaluate(
            @RequestBody EvaluateRequestDto request,
            HttpServletRequest http) {
        String role = request.role() == null
                ? "" : request.role().trim().toLowerCase(java.util.Locale.ROOT);
        if (!role.isBlank() && !SUPPORTED_ROLES.contains(role)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "unsupported_role");
            body.put("role", request.role());
            body.put("supported_roles", List.of("input", "output", "tool_call"));
            return ResponseEntity.badRequest().body(body);
        }
        java.util.Optional<Duration> limited = rateLimiter.check(
                request.sessionId(), clientIp(http), request.traceId(), request.streamChunk());
        if (limited.isPresent()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "rate_limited");
            body.put("retry_after_seconds", limited.get().toSeconds());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(limited.get().toSeconds()))
                    .body(body);
        }
        boolean isOutputReview = "output".equals(role);
        EvaluateResponseDto response = isOutputReview
                ? outputReview.review(request)
                : orchestrator.evaluate(request);
        return ResponseEntity.ok(response);
    }

    /** P1.2: STI Taint detection endpoint for tool return values. */
    @PostMapping("/v1/evaluate/tool-result")
    public io.virbius.engine.eval.ToolResultResponseDto evaluateToolResult(
            @RequestBody io.virbius.engine.eval.ToolResultRequestDto request) {
        return orchestrator.evaluateToolResult(request);
    }

    /** P1.3: LLM-based injection detection endpoint for memory writes. */
    @PostMapping("/v1/memory/check")
    public io.virbius.engine.eval.MemoryCheckResponseDto checkMemory(
            @RequestBody io.virbius.engine.eval.MemoryCheckRequestDto request) {
        return orchestrator.checkMemory(request);
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
