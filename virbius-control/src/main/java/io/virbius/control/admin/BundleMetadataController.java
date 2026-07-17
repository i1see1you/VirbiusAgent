package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.dto.request.GatewayRoutesRequest;
import io.virbius.control.domain.dto.request.SceneRegistryRequest;
import io.virbius.control.service.BundleMetadataService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/bundles/{bundleId}/versions/{version}/metadata")
public class BundleMetadataController {

    private final BundleMetadataService metadataService;

    public BundleMetadataController(BundleMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> getMetadata(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("bundleId") String bundleId,
            @PathVariable("version") String version) {
        return ApiResult.ok(metadataService.getMetadata(tenantId, bundleId, version));
    }

    @PutMapping("/gateway-routes")
    public ApiResult<Map<String, Object>> updateGatewayRoutes(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("bundleId") String bundleId,
            @PathVariable("version") String version,
            @RequestBody GatewayRoutesRequest body,
            @RequestParam(value = "sync", defaultValue = "false") boolean sync) {
        return ApiResult.ok(metadataService.updateGatewayRoutes(tenantId, bundleId, version, body, sync));
    }

    @PutMapping("/scene-registry")
    public ApiResult<Map<String, Object>> updateSceneRegistry(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("bundleId") String bundleId,
            @PathVariable("version") String version,
            @RequestBody SceneRegistryRequest body,
            @RequestParam(value = "sync", defaultValue = "false") boolean sync) {
        return ApiResult.ok(metadataService.updateSceneRegistry(tenantId, bundleId, version, body, sync));
    }
}
