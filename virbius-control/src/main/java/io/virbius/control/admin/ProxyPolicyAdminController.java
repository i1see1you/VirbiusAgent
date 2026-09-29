package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.service.ProxyPolicyService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/proxy-policy")
public class ProxyPolicyAdminController {

    private final ProxyPolicyService proxyPolicyService;

    public ProxyPolicyAdminController(ProxyPolicyService proxyPolicyService) {
        this.proxyPolicyService = proxyPolicyService;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> get(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(proxyPolicyService.get(tenantId));
    }

    @PutMapping
    public ApiResult<Map<String, Object>> save(
            @PathVariable("tenantId") String tenantId,
            @RequestBody(required = false) Map<String, Object> body) {
        return ApiResult.ok(proxyPolicyService.save(tenantId, body));
    }
}
