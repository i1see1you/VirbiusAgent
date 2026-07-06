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
@RequestMapping("/api/v1/admin/platform/api-credentials")
public class PlatformApiCredentialAdminController {

    private final TenantApiCredentialService credentialService;

    public PlatformApiCredentialAdminController(TenantApiCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        return ApiResult.ok(credentialService.listForTenant("*"));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> issue(@RequestBody IssueApiCredentialRequest body) {
        ApiRole role = body.role() != null && !body.role().isBlank()
                ? ApiRole.parse(body.role())
                : ApiRole.PLATFORM_ADMIN;
        return ApiResult.ok(credentialService.issuePlatform(role, body.label()));
    }

    @PostMapping("/{credentialId}/revoke")
    public ApiResult<Map<String, Object>> revoke(@PathVariable("credentialId") String credentialId) {
        credentialService.revoke("*", credentialId);
        return ApiResult.ok(Map.of("credential_id", credentialId, "status", "revoked"));
    }
}
