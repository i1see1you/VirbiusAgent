package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.CumulativeDef;
import io.virbius.control.service.AccessListService;
import io.virbius.control.service.CumulativeService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/cumulatives")
public class CumulativeAdminController {

    private final CumulativeService cumulativeService;
    private final AccessListService accessListService;

    public CumulativeAdminController(CumulativeService cumulativeService, AccessListService accessListService) {
        this.cumulativeService = cumulativeService;
        this.accessListService = accessListService;
    }

    @GetMapping
    public ApiResult<List<CumulativeDef>> list(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(cumulativeService.list(tenantId));
    }

    @GetMapping("/{cumulativeName}")
    public ApiResult<CumulativeDef> get(@PathVariable("tenantId") String tenantId, @PathVariable("cumulativeName") String cumulativeName) {
        return ApiResult.ok(cumulativeService.get(tenantId, cumulativeName));
    }

    @PutMapping("/{cumulativeName}")
    public ApiResult<Map<String, Object>> upsert(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("cumulativeName") String cumulativeName,
            @RequestBody CumulativeDef body) {
        CumulativeDef def = new CumulativeDef(
                tenantId,
                cumulativeName,
                body.description(),
                body.dimension(),
                body.windowKind(),
                body.windowMinutes(),
                body.windowHours(),
                body.timezone(),
                body.priority(),
                body.status() != null ? body.status() : "active",
                body.ingestPredicateRuntime(),
                body.ingestPredicate());
        CumulativeDef saved = cumulativeService.upsert(def);
        Map<String, Object> sync = accessListService.refreshArtifacts(tenantId);
        return ApiResult.ok(Map.of("definition", saved, "artifact_sync", sync));
    }

    @DeleteMapping("/{cumulativeName}")
    public ApiResult<Map<String, Object>> delete(
            @PathVariable("tenantId") String tenantId, @PathVariable("cumulativeName") String cumulativeName) {
        cumulativeService.delete(tenantId, cumulativeName);
        Map<String, Object> sync = accessListService.refreshArtifacts(tenantId);
        return ApiResult.ok(Map.of("deleted", true, "cumulative_name", cumulativeName, "artifact_sync", sync));
    }
}
