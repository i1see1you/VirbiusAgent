package io.virbius.control.service;

import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.domain.dto.request.AccessListEntryInput;
import io.virbius.control.gateway.artifact.GatewayArtifactPublisher;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.control.repository.RegistryRepository;
import io.virbius.policy.ListStorageKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AccessListService {

    /** Max active (non-expired) entries per memory list (keyword / ip_cidr). */
    public static final int MEMORY_LIST_MAX_ACTIVE_ENTRIES = 1000;

    private final ListMetaRepository listMetaRepo;
    private final RegistryRepository registryRepo;
    private final PublishService publishService;
    private final ArtifactService artifactService;
    private final GatewayArtifactPublisher gatewayArtifactPublisher;

    public AccessListService(
            ListMetaRepository listMetaRepo,
            RegistryRepository registryRepo,
            PublishService publishService,
            ArtifactService artifactService,
            GatewayArtifactPublisher gatewayArtifactPublisher) {
        this.listMetaRepo = listMetaRepo;
        this.registryRepo = registryRepo;
        this.publishService = publishService;
        this.artifactService = artifactService;
        this.gatewayArtifactPublisher = gatewayArtifactPublisher;
    }

    public Map<String, Object> getAll(String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenantId);
        List<Map<String, Object>> lists = new ArrayList<>();
        for (AccessListMeta meta : listMetaRepo.listMeta(tenantId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("list_name", meta.listName());
            item.put("dimension", meta.dimension());
            item.put("storage", storageKind(meta.dimension()).name().toLowerCase());
            item.put("remark", meta.remark());
            List<AccessListEntry> entries = listMetaRepo.listEntries(tenantId, meta.listName());
            item.put("entries", entryMaps(entries));
            if (storageKind(meta.dimension()) == ListStorageKind.MEMORY) {
                item.put("active_entry_count", countActiveEntries(entries, Instant.now()));
            }
            lists.add(item);
        }
        out.put("lists", lists);
        return out;
    }

    public List<AccessListEntry> getEntries(String tenantId, String listName) {
        listMetaRepo.getMeta(tenantId, listName).orElseThrow(() -> new IllegalArgumentException("list not found"));
        return listMetaRepo.listEntries(tenantId, listName);
    }

    public Map<String, Object> addEntry(
            String tenantId, String listName, String value, String remark, Instant expiresAt) {
        AccessListMeta meta = listMetaRepo
                .getMeta(tenantId, listName)
                .orElseThrow(() -> new IllegalArgumentException("list not found"));
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("value required");
        }
        Instant now = Instant.now();
        ensureMemoryListCapacity(meta, listMetaRepo.listEntries(tenantId, listName), normalized, expiresAt, now);
        boolean added = listMetaRepo.addEntry(tenantId, listName, normalized, remark, expiresAt);
        return refreshArtifactsAndPush(tenantId, Map.of("added", added));
    }

    public AccessListMeta upsertMetaAndPush(String tenantId, AccessListMeta meta) {
        listMetaRepo.upsertMeta(meta);
        refreshArtifactsAndPush(tenantId, Map.of());
        return meta;
    }

    public Map<String, Object> removeNamedEntryAndPush(String tenantId, String listName, String value) {
        listMetaRepo.getMeta(tenantId, listName).orElseThrow(() -> new IllegalArgumentException("list not found"));
        boolean removed = listMetaRepo.removeEntry(tenantId, listName, value);
        return refreshArtifactsAndPush(tenantId, Map.of("removed", removed));
    }

    public Map<String, Object> deleteListAndPush(String tenantId, String listName) {
        listMetaRepo.deleteMeta(tenantId, listName);
        return refreshArtifactsAndPush(tenantId, Map.of());
    }

    public Map<String, Object> replaceNamedEntries(String tenantId, String listName, List<AccessListEntryInput> inputs) {
        AccessListMeta meta = listMetaRepo
                .getMeta(tenantId, listName)
                .orElseThrow(() -> new IllegalArgumentException("list not found"));
        List<AccessListEntry> entries = new ArrayList<>();
        if (inputs != null) {
            for (AccessListEntryInput in : inputs) {
                if (in == null || in.value() == null || in.value().isBlank()) {
                    continue;
                }
                String v = AccessListEntryValidator.normalizeAndValidate(
                        meta.dimension(), in.value().trim(), bundleMetadata(tenantId));
                entries.add(new AccessListEntry(v, in.remark(), null, in.expiresAt()));
            }
        }
        listMetaRepo.replaceEntries(tenantId, listName, entries);
        return refreshArtifactsAndPush(tenantId, Map.of());
    }

    public Map<String, Object> pushToEngine(String tenantId) {
        return Map.of("engine_reload", publishService.runtimeSnapshot(tenantId));
    }

    public Map<String, Object> refreshArtifacts(String tenantId) {
        return refreshArtifacts(tenantId, "access_list");
    }

    public Map<String, Object> refreshArtifacts(String tenantId, String trigger) {
        Map<String, Object> metadata = bundleMetadata(tenantId);
        Map<String, String> artifacts = artifactService.write(tenantId, metadata);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("refreshed", true);
        summary.put("artifacts", artifacts);
        try {
            gatewayArtifactPublisher.publishIfEnabled(tenantId, metadata, trigger).ifPresent(result -> {
                summary.put("gateway_redis", result);
                summary.put("gateway_sync_ack", result.syncAck());
            });
        } catch (Exception ex) {
            summary.put("gateway_redis_error", ex.getMessage());
        }
        return summary;
    }

    public Map<String, Object> syncRules(String tenantId) {
        return refreshArtifactsAndPush(tenantId, Map.of());
    }

    public Map<String, Object> refreshArtifactsAndPush(String tenantId) {
        return refreshArtifactsAndPush(tenantId, Map.of());
    }

    private Map<String, Object> refreshArtifactsAndPush(String tenantId, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>(refreshArtifacts(tenantId));
        out.putAll(extra);
        out.put("engine_reload", publishService.runtimeSnapshot(tenantId));
        return out;
    }

    private Map<String, Object> bundleMetadata(String tenantId) {
        return registryRepo
                .getBundle(tenantId, "poc-default", "0.1.0")
                .map(b -> b.metadata() != null ? b.metadata() : Map.<String, Object>of())
                .orElse(Map.of());
    }

    static ListStorageKind storageKind(String dimension) {
        return ListStorageKind.fromDimension(dimension);
    }

    static long countActiveEntries(List<AccessListEntry> entries, Instant now) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        return entries.stream().filter(e -> isActiveEntry(e, now)).count();
    }

    static boolean isActiveEntry(AccessListEntry entry, Instant now) {
        if (entry == null) {
            return false;
        }
        Instant exp = entry.expiresAt();
        return exp == null || exp.isAfter(now);
    }

    static void ensureMemoryListCapacity(
            AccessListMeta meta,
            List<AccessListEntry> existing,
            String newValue,
            Instant newExpiresAt,
            Instant now) {
        if (storageKind(meta.dimension()) != ListStorageKind.MEMORY) {
            return;
        }
        boolean duplicate = existing.stream().anyMatch(e -> newValue.equals(e.value()));
        if (duplicate) {
            return;
        }
        if (!willBeActive(newExpiresAt, now)) {
            return;
        }
        if (countActiveEntries(existing, now) >= MEMORY_LIST_MAX_ACTIVE_ENTRIES) {
            throw new IllegalArgumentException(
                    "Memory list active entries have reached the limit (" + MEMORY_LIST_MAX_ACTIVE_ENTRIES + "), cannot add more");
        }
    }

    private static boolean willBeActive(Instant expiresAt, Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    public static List<Map<String, Object>> entryMaps(List<AccessListEntry> entries) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AccessListEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", e.value());
            if (e.remark() != null) {
                m.put("remark", e.remark());
            }
            if (e.createdAt() != null) {
                m.put("created_at", e.createdAt().toString());
            }
            if (e.expiresAt() != null) {
                m.put("expires_at", e.expiresAt().toString());
            }
            out.add(m);
        }
        return out;
    }
}
