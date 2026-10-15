package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.service.EvaluateTokenService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin surface for evaluate-token management (protected by the API-key /
 * operator-JWT filter — everything under {@code /api/v1/admin/}).
 *
 * <ul>
 *   <li>Service tokens: per-purpose tokens for internal facades and the gateway.
 *       Wildcards are granted ONLY to explicitly cross-tenant service tokens.</li>
 *   <li>Token revocation by jti (single-token granularity).</li>
 *   <li>Idempotent issuer-set republish (Redis-loss recovery path, N1).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/evaluate-token")
public class EvaluateTokenAdminController {

    private final EvaluateTokenService service;

    public EvaluateTokenAdminController(EvaluateTokenService service) {
        this.service = service;
    }

    public record ServiceTokenRequest(
            String purpose, Boolean crossTenant, List<String> allowedTools, Long ttlSeconds) {}

    @PostMapping("/service")
    public ApiResult<?> serviceToken(@RequestBody ServiceTokenRequest body) {
        if (body == null || body.purpose() == null || body.purpose().isBlank()) {
            return ApiResult.error(400, "purpose required");
        }
        boolean crossTenant = Boolean.TRUE.equals(body.crossTenant());
        List<String> tools = body.allowedTools();
        if (tools == null || tools.isEmpty() || tools.contains("*")) {
            tools = crossTenant ? List.of("*") : List.of();
        }
        return ApiResult.ok(service.mintServiceToken(
                body.purpose(), crossTenant, tools, body.ttlSeconds()));
    }

    @PostMapping("/revoke")
    public ApiResult<?> revoke(@RequestBody Map<String, String> body) {
        String jti = body == null ? null : body.get("jti");
        if (jti == null || jti.isBlank()) {
            return ApiResult.error(400, "jti required");
        }
        service.revokeByJti(jti, body.getOrDefault("reason", "revoked via admin"));
        return ApiResult.ok(Map.of("revoked", true, "jti", jti));
    }

    /** Idempotent republish of the issuer set (Redis-loss recovery, N1). */
    @PostMapping("/republish-issuers")
    public ApiResult<?> republish() {
        service.publishIssuers();
        return ApiResult.ok(Map.of("republished", true));
    }
}
