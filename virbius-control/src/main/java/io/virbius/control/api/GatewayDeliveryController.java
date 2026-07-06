package io.virbius.control.api;

import io.virbius.control.gateway.artifact.GatewayArtifactPart;
import io.virbius.control.service.GatewayDeliveryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/gateway/tenants/{tenantId}")
public class GatewayDeliveryController {

    private final GatewayDeliveryService gatewayDeliveryService;

    public GatewayDeliveryController(GatewayDeliveryService gatewayDeliveryService) {
        this.gatewayDeliveryService = gatewayDeliveryService;
    }

    @GetMapping("/policy-version")
    public java.util.Map<String, Object> policyVersion(@PathVariable("tenantId") String tenantId) {
        return gatewayDeliveryService.policyVersion(tenantId);
    }

    @GetMapping(value = "/snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> snapshot(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(value = "part", defaultValue = "access-lists") String part,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        var meta = gatewayDeliveryService.requireMeta(tenantId);
        String etag = String.valueOf(meta.artifactRevision());
        if (ifNoneMatch != null && etag.equals(ifNoneMatch.trim())) {
            return ResponseEntity.status(304).eTag(etag).build();
        }
        GatewayArtifactPart artifactPart = "scene-registry".equals(part)
                ? GatewayArtifactPart.SCENE_REGISTRY
                : GatewayArtifactPart.ACCESS_LISTS;
        byte[] body = gatewayDeliveryService.readPartBytes(tenantId, artifactPart);
        String sha = artifactPart == GatewayArtifactPart.ACCESS_LISTS
                ? meta.accessListsSha256()
                : meta.sceneRegistrySha256();
        return ResponseEntity.ok()
                .eTag(etag)
                .header("X-Content-Sha256", sha)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body);
    }
}
