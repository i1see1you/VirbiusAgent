package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.BundleVersion;
import io.virbius.control.service.AccessListService;
import io.virbius.control.service.BundleMetadataService;
import io.virbius.control.service.DeployStateService;
import io.virbius.control.repository.RegistryRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/dashboard")
public class DashboardAdminController {

    private final RegistryRepository store;
    private final AccessListService accessListService;
    private final BundleMetadataService metadataService;
    private final DeployStateService deployStateService;

    public DashboardAdminController(
            RegistryRepository store, AccessListService accessListService,
            BundleMetadataService metadataService, DeployStateService deployStateService) {
        this.store = store;
        this.accessListService = accessListService;
        this.metadataService = metadataService;
        this.deployStateService = deployStateService;
    }

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview(@PathVariable("tenantId") String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenantId);
        out.put("ui", Map.of("console", "/ui"));
        out.put("deploy_status", deployStateService.status(tenantId));
        List<Map<String, Object>> bundles = new ArrayList<>();
        for (BundleVersion b : store.listBundles(tenantId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bundle_id", b.bundleId());
            item.put("version", b.version());
            bundles.add(item);
        }
        out.put("bundles", bundles);
        out.put("access_lists", accessListService.getAll(tenantId));
        if (!bundles.isEmpty()) {
            BundleVersion first = store.listBundles(tenantId).get(0);
            try {
                out.put("default_bundle_metadata", metadataService.getMetadata(tenantId, first.bundleId(), first.version()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return ApiResult.ok(out);
    }
}