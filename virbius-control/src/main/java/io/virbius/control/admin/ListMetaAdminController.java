package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.domain.AccessListMetaDimension;
import io.virbius.control.domain.dto.request.AccessListEntriesRequest;
import io.virbius.control.domain.dto.request.AccessListEntryInput;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.control.service.AccessListService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/lists")
public class ListMetaAdminController {

    private final ListMetaRepository listMetaRepo;
    private final AccessListService accessListService;

    public ListMetaAdminController(ListMetaRepository listMetaRepo, AccessListService accessListService) {
        this.listMetaRepo = listMetaRepo;
        this.accessListService = accessListService;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> listAll(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(accessListService.getAll(tenantId));
    }

    @GetMapping("/{listName}")
    public ApiResult<Map<String, Object>> get(@PathVariable("tenantId") String tenantId, @PathVariable("listName") String listName) {
        AccessListMeta meta = listMetaRepo
                .getMeta(tenantId, listName)
                .orElseThrow(() -> new IllegalArgumentException("list not found: " + listName));
        return ApiResult.ok(Map.of(
                "meta", meta,
                "entries", AccessListService.entryMaps(listMetaRepo.listEntries(tenantId, listName))));
    }

    @PutMapping("/{listName}")
    public ApiResult<AccessListMeta> upsertMeta(
            @PathVariable("tenantId") String tenantId, @PathVariable("listName") String listName, @RequestBody AccessListMeta body) {
        if (body.dimension() == null || body.dimension().isBlank()) {
            throw new IllegalArgumentException("dimension required");
        }
        String dimension = AccessListMetaDimension.validate(body.dimension());
        AccessListMeta meta = new AccessListMeta(tenantId, listName, dimension, body.remark());
        return ApiResult.ok(accessListService.upsertMetaAndPush(tenantId, meta));
    }

    @PutMapping("/{listName}/entries")
    public ApiResult<Map<String, Object>> replaceEntries(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("listName") String listName,
            @RequestBody AccessListEntriesRequest req) {
        List<AccessListEntryInput> inputs = resolveEntryInputs(req);
        return ApiResult.ok(accessListService.replaceNamedEntries(tenantId, listName, inputs));
    }

    @PostMapping("/{listName}/entries")
    public ApiResult<Map<String, Object>> addEntry(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("listName") String listName,
            @RequestBody AccessListEntriesRequest req) {
        AccessListEntryInput one = singleEntryInput(req);
        return ApiResult.ok(accessListService.addEntry(
                tenantId, listName, one.value(), one.remark(), one.expiresAt()));
    }

    @DeleteMapping("/{listName}/entries/{value}")
    public ApiResult<Map<String, Object>> removeEntry(
            @PathVariable("tenantId") String tenantId, @PathVariable("listName") String listName, @PathVariable("value") String value) {
        return ApiResult.ok(accessListService.removeNamedEntryAndPush(tenantId, listName, value));
    }

    @DeleteMapping("/{listName}")
    public ApiResult<Map<String, Object>> delete(@PathVariable("tenantId") String tenantId, @PathVariable("listName") String listName) {
        return ApiResult.ok(accessListService.deleteListAndPush(tenantId, listName));
    }

    private static List<AccessListEntryInput> resolveEntryInputs(AccessListEntriesRequest req) {
        if (req.entries() != null && !req.entries().isEmpty()) {
            return req.entries();
        }
        List<AccessListEntryInput> out = new ArrayList<>();
        if (req.values() != null) {
            for (String v : req.values()) {
                out.add(new AccessListEntryInput(v, null, null));
            }
        } else if (req.value() != null && !req.value().isBlank()) {
            out.add(new AccessListEntryInput(req.value(), null, null));
        }
        return out;
    }

    private static AccessListEntryInput singleEntryInput(AccessListEntriesRequest req) {
        if (req.entries() != null && !req.entries().isEmpty()) {
            return req.entries().get(0);
        }
        if (req.value() != null && !req.value().isBlank()) {
            return new AccessListEntryInput(req.value(), null, null);
        }
        if (req.values() != null && !req.values().isEmpty()) {
            return new AccessListEntryInput(req.values().get(0), null, null);
        }
        throw new IllegalArgumentException("value required");
    }

    @PostMapping("/sync-rules")
    public ApiResult<Map<String, Object>> syncRules(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(accessListService.syncRules(tenantId));
    }

    @PostMapping("/push-engine")
    public ApiResult<Map<String, Object>> pushEngine(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(accessListService.pushToEngine(tenantId));
    }


}
