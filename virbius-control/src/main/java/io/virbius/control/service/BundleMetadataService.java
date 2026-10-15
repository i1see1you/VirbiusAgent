package io.virbius.control.service;

import io.virbius.control.domain.BundleVersion;
import io.virbius.control.domain.dto.request.GatewayRouteInput;
import io.virbius.control.domain.dto.request.GatewayRoutesRequest;
import io.virbius.control.gateway.GatewayRoutesHelper;
import io.virbius.control.repository.RegistryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BundleMetadataService {

    private final RegistryRepository store;
    private final AccessListService accessListService;

    public BundleMetadataService(RegistryRepository store, AccessListService accessListService) {
        this.store = store;
        this.accessListService = accessListService;
    }

    // ===================== metadata aggregate query =====================

    public Map<String, Object> getMetadata(String tenantId, String bundleId, String version) {
        BundleVersion bundle = requireBundle(tenantId, bundleId, version);
        Map<String, Object> metadata = bundle.metadata() != null ? bundle.metadata() : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenantId);
        out.put("bundle_id", bundleId);
        out.put("version", version);
        out.put("status", bundle.status());
        out.put("metadata", metadata);
        out.put("gateway", GatewayRoutesHelper.gatewayBlock(metadata));
        out.put("gateway_routes", GatewayRoutesHelper.parseRoutes(metadata).stream().map(this::routeToMap).toList());
        return out;
    }

    // ===================== gateway routes =====================

    public Map<String, Object> updateGatewayRoutes(
            String tenantId, String bundleId, String version, GatewayRoutesRequest body, boolean syncArtifacts) {
        if (body == null || body.routes() == null) {
            throw new IllegalArgumentException("routes required");
        }
        GatewayRoutesHelper.validateRoutes(body.routes());
        BundleVersion bundle = requireDraftBundle(tenantId, bundleId, version);
        Map<String, Object> metadata = new LinkedHashMap<>(
                bundle.metadata() != null ? bundle.metadata() : Map.of());
        Map<String, Object> gateway = GatewayRoutesHelper.toGatewayMetadata(
                GatewayRoutesHelper.gatewayBlock(metadata),
                body.evaluate(),
                body.failMode(),
                body.cloudScan(),
                body.routes());
        metadata.put("gateway", gateway);
        store.updateBundleMetadata(tenantId, bundleId, version, metadata, bundle.metadataVersion());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", true);
        out.put("gateway", gateway);
        out.put("gateway_routes", body.routes().stream().map(this::routeToMap).toList());
        out.put("scope", metadata.get("scope"));
        if (syncArtifacts) {
            out.put("sync", accessListService.syncRules(tenantId));
        }
        return out;
    }

    private Map<String, Object> routeToMap(GatewayRouteInput r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uri", r.uri());
        m.put("methods", r.methods() != null ? r.methods() : List.of("POST"));
        return m;
    }

    public Map<String, Object> syncGatewayArtifacts(String tenantId) {
        return accessListService.syncRules(tenantId);
    }

    // ===================== helpers =====================

    private BundleVersion requireBundle(String tenantId, String bundleId, String version) {
        return store.getBundle(tenantId, bundleId, version)
                .orElseThrow(() -> new IllegalArgumentException("bundle not found"));
    }

    private BundleVersion requireDraftBundle(String tenantId, String bundleId, String version) {
        BundleVersion bundle = requireBundle(tenantId, bundleId, version);
        if (!"draft".equalsIgnoreCase(bundle.status())) {
            throw new IllegalArgumentException(
                    "bundle is not editable in status '" + bundle.status()
                            + "'; derive a new version to modify factors or metadata");
        }
        return bundle;
    }

    private static Map<String, Object> castStringObjectMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null) {
                out.put(String.valueOf(k), v);
            }
        });
        return out;
    }
}
