package io.virbius.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.common.exception.ResourceNotFoundException;
import io.virbius.control.domain.ToolRegistryEntry;
import io.virbius.control.repository.ToolRegistryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manages the tool registry — the canonical source of tool metadata.
 *
 * <p>Each tool has exactly one definition per tenant.
 */
@Service
public class ToolRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryService.class);

    private final ToolRegistryRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolRegistryService(ToolRegistryRepository repo) {
        this.repo = repo;
    }

    public List<Map<String, Object>> list(String tenantId) {
        return repo.list(tenantId).stream()
                .map(this::toMap)
                .toList();
    }

    public Map<String, Object> get(String tenantId, String toolName) {
        return repo.get(tenantId, toolName)
                .map(this::toMap)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tool not found: " + toolName));
    }

    public Map<String, Object> upsert(String tenantId, UpsertToolRequest req) {
        String schemaJson = null;
        if (req.allowedArgsSchema() != null && !req.allowedArgsSchema().isBlank()) {
            try {
                Object parsed = mapper.readValue(req.allowedArgsSchema(), Object.class);
                schemaJson = mapper.writeValueAsString(parsed);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "allowed_args_schema is not valid JSON: " + e.getMessage());
            }
        }

        ToolRegistryEntry entry = ToolRegistryEntry.create(
                tenantId,
                req.toolName(),
                req.riskClass() != null ? req.riskClass() : "low",
                req.sandboxType() != null ? req.sandboxType() : "none",
                req.timeoutMs() > 0 ? req.timeoutMs() : 30000,
                req.fastPath() != null ? req.fastPath() : false,
                schemaJson,
                req.description(),
                req.approvalMode() != null ? req.approvalMode() : "strict");

        repo.upsert(entry);
        log.info("tool registry upsert: tenant={} tool={} risk={} sandbox={} timeout={} fastPath={} approvalMode={}",
                tenantId, entry.toolName(), entry.riskClass(), entry.sandboxType(),
                entry.timeoutMs(), entry.fastPath(), entry.approvalMode());
        return get(tenantId, entry.toolName());
    }

    public void delete(String tenantId, String toolName) {
        repo.delete(tenantId, toolName);
        log.info("tool registry delete: tenant={} tool={}", tenantId, toolName);
    }

    /**
     * Build tool policy blocks for the Edge Manifest.
     * Called by {@link io.virbius.control.service.ArtifactService}.
     */
    public List<Map<String, Object>> buildToolPolicyBlocks(String tenantId) {
        return repo.list(tenantId).stream()
                .map(this::toManifestBlock)
                .toList();
    }

    /**
     * Build tool policy blocks for the Engine runtime snapshot.
     * Called by {@link io.virbius.control.service.PublishService}.
     */
    public List<Map<String, Object>> buildEngineToolPolicies(String tenantId) {
        return repo.list(tenantId).stream()
                .map(this::toManifestBlock)
                .toList();
    }

    private Map<String, Object> toMap(ToolRegistryEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant_id", e.tenantId());
        m.put("tool_name", e.toolName());
        m.put("risk_class", e.riskClass());
        m.put("sandbox_type", e.sandboxType());
        m.put("timeout_ms", e.timeoutMs());
        m.put("fast_path", e.fastPath());
        m.put("approval_mode", e.approvalMode());
        if (e.allowedArgsSchemaJson() != null) {
            m.put("allowed_args_schema", e.allowedArgsSchemaJson());
        }
        if (e.description() != null) {
            m.put("description", e.description());
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toManifestBlock(ToolRegistryEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool_name", e.toolName());
        m.put("risk_class", e.riskClass());
        m.put("sandbox_type", e.sandboxType());
        m.put("timeout_ms", e.timeoutMs());
        m.put("fast_path", e.fastPath());
        m.put("approval_mode", e.approvalMode());
        if (e.allowedArgsSchemaJson() != null) {
            try {
                m.put("allowed_args_schema", mapper.readValue(e.allowedArgsSchemaJson(), Object.class));
            } catch (Exception ignored) {
                // If JSON is malformed, skip it — the DB CHECK constraint should prevent this
            }
        }
        return m;
    }

    public record UpsertToolRequest(
            String toolName,
            String riskClass,
            String sandboxType,
            int timeoutMs,
            Boolean fastPath,
            String allowedArgsSchema,
            String description,
            String approvalMode) {}
}
