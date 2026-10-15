package io.virbius.control.api;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.service.EvaluateTokenService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P0-min evaluate-token endpoints (public path; the License JWT itself is the
 * credential for the exchange, so these sit outside the admin API-key filter).
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/evaluate-token} — the ONLY exchange path: a
 *       caller presents its long-lived License JWT once and receives a
 *       short-lived (default 15min) capability-scoped token for
 *       {@code POST /v1/evaluate}. The License JWT never travels per-request.</li>
 *   <li>{@code GET /api/v1/auth/evaluate-token/public-key} — the platform public
 *       PEM (operators bake it into the engine's bootstrap trust anchor).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class EvaluateTokenController {

    private final EvaluateTokenService service;

    public EvaluateTokenController(EvaluateTokenService service) {
        this.service = service;
    }

    public record ExchangeRequest(String licenseJwt, String sid) {}

    @PostMapping("/evaluate-token")
    public ApiResult<?> exchange(@RequestBody ExchangeRequest body) {
        if (body == null || body.licenseJwt() == null || body.licenseJwt().isBlank()) {
            return ApiResult.error(400, "license_jwt required");
        }
        return ApiResult.ok(service.exchange(body.licenseJwt(), body.sid()));
    }

    @GetMapping("/evaluate-token/public-key")
    public ApiResult<?> publicKey() {
        return ApiResult.ok(Map.of(
                "kid", service.activeKid(),
                "public_key_pem", service.activePublicKeyPem()));
    }
}
