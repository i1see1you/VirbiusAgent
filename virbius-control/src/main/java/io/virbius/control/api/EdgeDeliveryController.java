package io.virbius.control.api;

import io.virbius.control.common.exception.ResourceNotFoundException;
import io.virbius.control.domain.EdgeArtifactMeta;
import io.virbius.control.service.EdgeDeliveryService;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Edge SDK pull API (scheme B): lightweight version probe + conditional manifest download.
 * Returns raw JSON (no {@code ApiResult} wrapper) for embedded clients.
 */
@RestController
@RequestMapping("/api/v1/edge/tenants/{tenantId}/apps/{appId}")
public class EdgeDeliveryController {

    private final EdgeDeliveryService edgeDeliveryService;

    public EdgeDeliveryController(EdgeDeliveryService edgeDeliveryService) {
        this.edgeDeliveryService = edgeDeliveryService;
    }

    @GetMapping("/policy-version")
    public Map<String, Object> policyVersion(
            @PathVariable("tenantId") String tenantId, @PathVariable("appId") String appId) {
        return edgeDeliveryService.policyVersion(tenantId, appId);
    }

    @GetMapping(value = "/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> manifest(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("appId") String appId,
            @RequestParam(value = "pool", defaultValue = "stable") String pool,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        String effectivePool = pool;
        EdgeArtifactMeta meta;
        try {
            meta = edgeDeliveryService.requireMeta(tenantId, appId, effectivePool);
        } catch (ResourceNotFoundException e) {
            if ("canary".equals(effectivePool)) {
                effectivePool = "stable";
                meta = edgeDeliveryService.requireMeta(tenantId, appId, effectivePool);
            } else {
                throw e;
            }
        }
        String etag = String.valueOf(meta.artifactRevision());
        if (ifNoneMatch != null && etag.equals(ifNoneMatch.trim())) {
            return ResponseEntity.status(304).eTag(etag).build();
        }
        byte[] body = edgeDeliveryService.readManifestBytes(tenantId, appId, effectivePool);
        return ResponseEntity.ok()
                .eTag(etag)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body);
    }
}
