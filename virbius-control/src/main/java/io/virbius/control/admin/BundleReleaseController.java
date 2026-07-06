package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.BundleRelease;
import io.virbius.control.service.BundleReleaseService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/bundles/{bundleId}/releases")
public class BundleReleaseController {

    private final BundleReleaseService releaseService;

    public BundleReleaseController(BundleReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @GetMapping
    public ApiResult<Object> list(@PathVariable String tenantId, @PathVariable String bundleId) {
        return ApiResult.ok(Map.of(
                "releases", releaseService.listReleases(tenantId, bundleId),
                "active_version", releaseService.getActiveVersion(tenantId, bundleId)));
    }

    @GetMapping("/active")
    public ApiResult<Map<String, Object>> active(@PathVariable String tenantId, @PathVariable String bundleId) {
        return ApiResult.ok(Map.of("active_version", releaseService.getActiveVersion(tenantId, bundleId)));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> publishRelease(
            @PathVariable String tenantId,
            @PathVariable String bundleId,
            @RequestBody(required = false) Map<String, Object> body) {
        String version = body != null && body.get("version") instanceof String v ? v
                : releaseService.nextVersion(tenantId, bundleId);
        return ApiResult.ok(releaseService.publishRelease(tenantId, bundleId, version));
    }

    @PostMapping("/{version}/rollback")
    public ApiResult<Map<String, Object>> rollback(
            @PathVariable String tenantId,
            @PathVariable String bundleId,
            @PathVariable String version) {
        return ApiResult.ok(releaseService.rollbackTo(tenantId, bundleId, version));
    }
}
