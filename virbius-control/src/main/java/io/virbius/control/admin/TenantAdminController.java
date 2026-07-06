package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.dto.request.CreateTenantRequest;
import io.virbius.control.domain.dto.request.UpdateTenantRequest;
import io.virbius.control.service.TenantService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants")
public class TenantAdminController {

    private final TenantService tenantService;

    public TenantAdminController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        return ApiResult.ok(tenantService.listTenants());
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody CreateTenantRequest body) {
        return ApiResult.ok(tenantService.createTenant(body.tenantId(), body.name()));
    }

    @GetMapping("/{tenantId}")
    public ApiResult<Map<String, Object>> get(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(tenantService.getTenant(tenantId));
    }

    @PatchMapping("/{tenantId}")
    public ApiResult<Map<String, Object>> update(
            @PathVariable("tenantId") String tenantId, @RequestBody UpdateTenantRequest body) {
        return ApiResult.ok(tenantService.updateTenantName(tenantId, body.name()));
    }
}
