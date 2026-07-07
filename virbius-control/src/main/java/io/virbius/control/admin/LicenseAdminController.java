package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.AgentLicense;
import io.virbius.control.service.LicenseService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Runtime License management.
 *
 * <ul>
 *   <li>POST /issue — issue a new License JWT</li>
 *   <li>POST /{licenseId}/revoke — revoke a License</li>
 *   <li>GET /list — list licenses for a tenant</li>
 *   <li>GET /public-key — get the tenant's Ed25519 public key (for edge verification)</li>
 *   <li>POST /rotate-key — rotate the signing key pair</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/licenses")
public class LicenseAdminController {

    private final LicenseService licenseService;

    public LicenseAdminController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @PostMapping("/issue")
    public ApiResult<Map<String, Object>> issueLicense(
            @PathVariable("tenantId") String tenantId,
            @RequestBody IssueLicenseRequest body) {
        return ApiResult.ok(licenseService.issueLicense(
                tenantId,
                body.appId(),
                body.allowedTools(),
                body.allowedScenes() != null ? body.allowedScenes() : List.of(),
                body.riskQuota() > 0 ? body.riskQuota() : 60,
                body.toolRateLimit() > 0 ? body.toolRateLimit() : 50,
                body.expirySeconds() > 0 ? body.expirySeconds() : 86400L));
    }

    @PostMapping("/{licenseId}/revoke")
    public ApiResult<Void> revokeLicense(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("licenseId") String licenseId,
            @RequestBody(required = false) RevokeRequest body) {
        String reason = body != null ? body.reason() : "manual_revoke";
        licenseService.revokeLicense(licenseId, "admin", reason);
        return ApiResult.ok();
    }

    @GetMapping("/list")
    public ApiResult<List<AgentLicense>> listLicenses(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(value = "status", required = false) String status) {
        return ApiResult.ok(licenseService.listLicenses(tenantId, status));
    }

    @GetMapping("/public-key")
    public ApiResult<Map<String, String>> getPublicKey(
            @PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(Map.of("public_key_pem", licenseService.getPublicKey(tenantId)));
    }

    @PostMapping("/rotate-key")
    public ApiResult<Map<String, Object>> rotateKey(
            @PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(licenseService.rotateKey(tenantId));
    }

    public record IssueLicenseRequest(
            String appId,
            List<String> allowedTools,
            List<String> allowedScenes,
            int riskQuota,
            int toolRateLimit,
            long expirySeconds) {}

    public record RevokeRequest(String reason) {}
}
