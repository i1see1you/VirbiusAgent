package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.dto.request.IssueApiCredentialRequest;
import io.virbius.control.security.ApiRole;
import io.virbius.control.service.TenantApiCredentialService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/api-credentials")
public class TenantApiCredentialAdminController {

    private final TenantApiCredentialService credentialService;

    public TenantApiCredentialAdminController(TenantApiCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(credentialService.listForTenant(tenantId));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> issue(
            @PathVariable("tenantId") String tenantId, @RequestBody IssueApiCredentialRequest body) {
        ApiRole role = ApiRole.parse(body.role());
        return ApiResult.ok(credentialService.issueForTenant(tenantId, role, body.label()));
    }

    @PostMapping("/{credentialId}/revoke")
    public ApiResult<Map<String, Object>> revoke(
            @PathVariable("tenantId") String tenantId, @PathVariable("credentialId") String credentialId) {
        credentialService.revoke(tenantId, credentialId);
        return ApiResult.ok(Map.of("credential_id", credentialId, "status", "revoked"));
    }
}
