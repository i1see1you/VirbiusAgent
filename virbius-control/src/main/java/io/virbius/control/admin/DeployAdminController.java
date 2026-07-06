package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.service.AccessListService;
import io.virbius.control.service.ArtifactService;
import io.virbius.control.service.BundleReleaseService;
import io.virbius.control.service.DeployStateService;
import io.virbius.control.service.PublishOrchestrator;
import io.virbius.control.service.PublishService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/deploy")
public class DeployAdminController {

    private final AccessListService accessListService;
    private final PublishService publishService;
    private final ArtifactService artifactService;
    private final DeployStateService deployStateService;
    private final BundleReleaseService releaseService;
    private final PublishOrchestrator orchestrator;

    public DeployAdminController(
            AccessListService accessListService,
            PublishService publishService,
            ArtifactService artifactService,
            DeployStateService deployStateService,
            BundleReleaseService releaseService,
            PublishOrchestrator orchestrator) {
        this.accessListService = accessListService;
        this.publishService = publishService;
        this.artifactService = artifactService;
        this.deployStateService = deployStateService;
        this.releaseService = releaseService;
        this.orchestrator = orchestrator;
    }

    @PostMapping("/gateway")
    public ApiResult<Map<String, Object>> deployGateway(@PathVariable("tenantId") String tenantId) {
        Map<String, Object> sync = accessListService.refreshArtifacts(tenantId, "deploy");
        deployStateService.record(tenantId, "gateway");
        Map<String, Object> out = new LinkedHashMap<>(sync);
        out.put("layer", "gateway");
        out.put("deployed", true);
        return ApiResult.ok(out);
    }

    @PostMapping("/cloud")
    public ApiResult<Map<String, Object>> deployCloud(@PathVariable("tenantId") String tenantId) {
        Map<String, Object> result = publishService.runtimeSnapshot(tenantId);
        deployStateService.record(tenantId, "cloud");
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("layer", "cloud");
        out.put("deployed", true);
        return ApiResult.ok(out);
    }

    @PostMapping("/edge")
    public ApiResult<Map<String, Object>> deployEdge(@PathVariable("tenantId") String tenantId) {
        Map<String, Object> summary = new LinkedHashMap<>(artifactService.writeEdgeOnly(tenantId));
        deployStateService.record(tenantId, "edge");
        Map<String, Object> out = new LinkedHashMap<>(summary);
        out.put("layer", "edge");
        out.put("deployed", true);
        return ApiResult.ok(out);
    }

    @PostMapping("/bundle/{bundleId}/publish")
    public ApiResult<Map<String, Object>> publishBundle(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("bundleId") String bundleId,
            @RequestBody(required = false) Map<String, Object> body) {
        String version = body != null && body.get("version") instanceof String v ? v
                : releaseService.nextVersion(tenantId, bundleId);
        Map<String, Object> result = releaseService.publishRelease(tenantId, bundleId, version);
        return ApiResult.ok(result);
    }

    @PostMapping("/bundle/{bundleId}/rollback/{version}")
    public ApiResult<Map<String, Object>> rollbackBundle(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("bundleId") String bundleId,
            @PathVariable("version") String version) {
        Map<String, Object> result = releaseService.rollbackTo(tenantId, bundleId, version);
        return ApiResult.ok(result);
    }
}
