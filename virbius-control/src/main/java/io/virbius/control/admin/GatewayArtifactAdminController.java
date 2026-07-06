package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.gateway.artifact.GatewayArtifactPublishResult;
import io.virbius.control.gateway.artifact.GatewayArtifactPublisher;
import io.virbius.control.service.AccessListService;
import io.virbius.control.service.GatewayDeliveryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/gateway-artifacts")
public class GatewayArtifactAdminController {

    private final GatewayDeliveryService gatewayDeliveryService;
    private final GatewayArtifactPublisher gatewayArtifactPublisher;
    private final AccessListService accessListService;

    public GatewayArtifactAdminController(
            GatewayDeliveryService gatewayDeliveryService,
            GatewayArtifactPublisher gatewayArtifactPublisher,
            AccessListService accessListService) {
        this.gatewayDeliveryService = gatewayDeliveryService;
        this.gatewayArtifactPublisher = gatewayArtifactPublisher;
        this.accessListService = accessListService;
    }

    @GetMapping("/policy-version")
    public ApiResult<Map<String, Object>> policyVersion(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(gatewayDeliveryService.adminDetail(tenantId));
    }

    @GetMapping("/nodes")
    public ApiResult<List<Map<String, String>>> nodes(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(gatewayDeliveryService.listNodes(tenantId));
    }

    @PostMapping("/refresh")
    public ApiResult<Map<String, Object>> refresh(@PathVariable("tenantId") String tenantId) {
        Map<String, Object> sync = accessListService.syncRules(tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sync", sync);
        Object gw = sync.get("gateway_redis");
        if (gw instanceof GatewayArtifactPublishResult result) {
            out.put("artifact_revision", result.artifactRevision());
            out.put("storage", result.storage());
        }
        return ApiResult.ok(out);
    }
}
