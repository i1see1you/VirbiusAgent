package io.virbius.control.service;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.common.exception.ResourceNotFoundException;
import io.virbius.control.domain.Tenant;
import io.virbius.control.domain.TenantRolloutPolicy;
import io.virbius.control.repository.TenantRepository;
import io.virbius.control.repository.TenantRolloutPolicyRepository;
import io.virbius.control.security.ApiKeyAuthContext;
import io.virbius.control.security.ApiRole;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final TenantRolloutPolicyRepository rolloutPolicyRepository;
    private final AccessListService accessListService;
    private final JdbcTemplate jdbc;

    public TenantService(
            TenantRepository tenantRepository,
            TenantRolloutPolicyRepository rolloutPolicyRepository,
            AccessListService accessListService,
            JdbcTemplate jdbc) {
        this.tenantRepository = tenantRepository;
        this.rolloutPolicyRepository = rolloutPolicyRepository;
        this.accessListService = accessListService;
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listTenants() {
        return tenantRepository.listAll().stream().map(this::toSummary).toList();
    }

    public Map<String, Object> getTenant(String tenantId) {
        Tenant tenant = tenantRepository
                .findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("tenant", tenantId));
        return toDetail(tenant);
    }

    @Transactional
    public Map<String, Object> createTenant(String tenantId, String name) {
        requirePlatformAdmin();
        TenantApiCredentialService.validateTenantIdFormat(tenantId);
        if (tenantRepository.exists(tenantId)) {
            throw new BusinessException(409, "tenant already exists: " + tenantId);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name required");
        }
        Instant now = Instant.now();
        tenantRepository.insert(new Tenant(tenantId, name.trim(), now));
        rolloutPolicyRepository.save(TenantRolloutPolicy.defaults(tenantId));
        accessListService.refreshArtifactsAndPush(tenantId);
        return toDetail(tenantRepository.findById(tenantId).orElseThrow());
    }

    public Map<String, Object> updateTenantName(String tenantId, String name) {
        requirePlatformAdmin();
        if (!tenantRepository.exists(tenantId)) {
            throw new ResourceNotFoundException("tenant", tenantId);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name required");
        }
        tenantRepository.updateName(tenantId, name.trim());
        return getTenant(tenantId);
    }

    private Map<String, Object> toSummary(Tenant tenant) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenant.tenantId());
        out.put("name", tenant.name());
        out.put("created_at", tenant.createdAt().toString());
        out.put("rule_count", countRules(tenant.tenantId()));
        return out;
    }

    private Map<String, Object> toDetail(Tenant tenant) {
        Map<String, Object> out = toSummary(tenant);
        out.put("list_meta_count", countListMeta(tenant.tenantId()));
        return out;
    }

    private int countRules(String tenantId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_rules_current WHERE tenant_id = ?", Integer.class, tenantId);
        return n != null ? n : 0;
    }

    private int countListMeta(String tenantId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_access_list_meta WHERE tenant_id = ?", Integer.class, tenantId);
        return n != null ? n : 0;
    }

    private static void requirePlatformAdmin() {
        var principal = ApiKeyAuthContext.current();
        if (principal != null && !principal.role().satisfies(ApiRole.PLATFORM_ADMIN)) {
            throw new BusinessException(403, "platform_admin required");
        }
    }
}
