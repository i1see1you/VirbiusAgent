package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.service.ToolRegistryService;
import io.virbius.control.service.ToolRegistryService.UpsertToolRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Tool Registry management.
 *
 * <p>Base path: {@code /api/v1/admin/tenants/{tenantId}/tools}
 *
 * <ul>
 *   <li>GET / — list all registered tools</li>
 *   <li>POST / — create or update a tool entry</li>
 *   <li>GET /{toolName} — get a specific tool</li>
 *   <li>DELETE /{toolName} — delete a tool</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/tools")
public class ToolRegistryAdminController {

    private final ToolRegistryService toolRegistryService;

    public ToolRegistryAdminController(ToolRegistryService toolRegistryService) {
        this.toolRegistryService = toolRegistryService;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(
            @PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(toolRegistryService.list(tenantId));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> upsert(
            @PathVariable("tenantId") String tenantId,
            @RequestBody UpsertToolRequest body) {
        return ApiResult.ok(toolRegistryService.upsert(tenantId, body));
    }

    @GetMapping("/{toolName}")
    public ApiResult<Map<String, Object>> get(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("toolName") String toolName) {
        return ApiResult.ok(toolRegistryService.get(tenantId, toolName));
    }

    @DeleteMapping("/{toolName}")
    public ApiResult<Void> delete(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("toolName") String toolName) {
        toolRegistryService.delete(tenantId, toolName);
        return ApiResult.ok();
    }
}
