package io.virbius.control.service;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.common.exception.ResourceNotFoundException;
import io.virbius.control.domain.TenantApiCredential;
import io.virbius.control.repository.TenantApiCredentialRepository;
import io.virbius.control.repository.TenantRepository;
import io.virbius.control.security.ApiKeyAuthContext;
import io.virbius.control.security.ApiKeyHasher;
import io.virbius.control.security.ApiKeyPrincipal;
import io.virbius.control.security.ApiRole;
import io.virbius.control.security.TenantApiCredentialConstants;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class TenantApiCredentialService {

    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{1,62}$");

    private final TenantApiCredentialRepository repository;
    private final TenantRepository tenantRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public TenantApiCredentialService(
            TenantApiCredentialRepository repository, TenantRepository tenantRepository) {
        this.repository = repository;
        this.tenantRepository = tenantRepository;
    }

    public Optional<TenantApiCredential> findActiveByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String hash = ApiKeyHasher.sha256Hex(rawToken.trim());
        return repository.findActiveByKeyHash(hash);
    }

    public void touchLastUsed(String credentialId) {
        repository.touchLastUsed(credentialId);
    }

    public List<Map<String, Object>> listForTenant(String tenantId) {
        if (TenantApiCredential.PLATFORM_TENANT.equals(tenantId)) {
            return repository.listPlatformCredentials().stream().map(this::toPublicView).toList();
        }
        requireTenantExists(tenantId);
        return repository.listByTenant(tenantId).stream().map(this::toPublicView).toList();
    }

    public Map<String, Object> issueForTenant(String tenantId, ApiRole role, String label) {
        ApiKeyPrincipal issuer = ApiKeyAuthContext.require();
        if (TenantApiCredential.PLATFORM_TENANT.equals(tenantId)) {
            throw new IllegalArgumentException("use platform credential endpoint for platform keys");
        }
        requireTenantExists(tenantId);
        assertIssuerMayIssue(issuer, tenantId, role);
        return issueInternal(tenantId, role, label, issuer.credentialId());
    }

    public Map<String, Object> issuePlatform(ApiRole role, String label) {
        ApiKeyPrincipal issuer = ApiKeyAuthContext.require();
        if (!issuer.role().satisfies(ApiRole.PLATFORM_ADMIN)) {
            throw new BusinessException(403, "platform_admin required to issue platform credentials");
        }
        if (role != ApiRole.PLATFORM_ADMIN) {
            throw new IllegalArgumentException("platform endpoint only issues platform_admin keys");
        }
        return issueInternal(TenantApiCredential.PLATFORM_TENANT, role, label, issuer.credentialId());
    }

    public void revoke(String tenantId, String credentialId) {
        ApiKeyPrincipal issuer = ApiKeyAuthContext.require();
        TenantApiCredential target = repository.listByTenant(tenantId).stream()
                .filter(c -> c.credentialId().equals(credentialId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("api credential", credentialId));
        assertIssuerMayRevoke(issuer, target);
        repository.revoke(tenantId, credentialId);
    }

    private Map<String, Object> issueInternal(String tenantId, ApiRole role, String label, String createdBy) {
        String apiKey = generateApiKey();
        String hash = ApiKeyHasher.sha256Hex(apiKey);
        String credentialId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String safeLabel = label != null && !label.isBlank() ? label.trim() : null;
        TenantApiCredential stored = new TenantApiCredential(
                credentialId,
                tenantId,
                role,
                hash,
                apiKey.substring(0, Math.min(TenantApiCredentialConstants.KEY_PREFIX_LEN, apiKey.length())),
                safeLabel,
                TenantApiCredential.STATUS_ACTIVE,
                createdBy,
                now,
                null,
                null);
        repository.insert(stored);
        Map<String, Object> out = new LinkedHashMap<>(toPublicView(stored));
        out.put("api_key", apiKey);
        return out;
    }

    private void assertIssuerMayIssue(ApiKeyPrincipal issuer, String tenantId, ApiRole role) {
        if (role == ApiRole.PLATFORM_ADMIN) {
            throw new BusinessException(403, "forbidden_escalation: cannot issue platform_admin via tenant endpoint");
        }
        if (issuer.role() == ApiRole.PLATFORM_ADMIN) {
            return;
        }
        if (issuer.role() != ApiRole.TENANT_ADMIN) {
            throw new BusinessException(403, "tenant_admin required to issue credentials");
        }
        if (!issuer.tenantId().equals(tenantId)) {
            throw new BusinessException(403, "tenant scope mismatch");
        }
        if (role == ApiRole.TENANT_ADMIN && issuer.role() != ApiRole.TENANT_ADMIN) {
            throw new BusinessException(403, "forbidden_escalation");
        }
    }

    private void assertIssuerMayRevoke(ApiKeyPrincipal issuer, TenantApiCredential target) {
        if (target.role() == ApiRole.PLATFORM_ADMIN && issuer.role() != ApiRole.PLATFORM_ADMIN) {
            throw new BusinessException(403, "platform_admin required to revoke platform credentials");
        }
        if (issuer.role() == ApiRole.PLATFORM_ADMIN) {
            return;
        }
        if (issuer.role() != ApiRole.TENANT_ADMIN) {
            throw new BusinessException(403, "tenant_admin required to revoke credentials");
        }
        if (!issuer.tenantId().equals(target.tenantId())) {
            throw new BusinessException(403, "tenant scope mismatch");
        }
    }

    private void requireTenantExists(String tenantId) {
        if (!tenantRepository.exists(tenantId)) {
            throw new ResourceNotFoundException("tenant", tenantId);
        }
    }

    private Map<String, Object> toPublicView(TenantApiCredential c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("credential_id", c.credentialId());
        out.put("tenant_id", c.tenantId());
        out.put("role", c.role().value());
        out.put("key_prefix", c.keyPrefix());
        out.put("label", c.label());
        out.put("status", c.status());
        out.put("created_by", c.createdBy());
        out.put("created_at", c.createdAt().toString());
        out.put("revoked_at", c.revokedAt() != null ? c.revokedAt().toString() : null);
        out.put("last_used_at", c.lastUsedAt() != null ? c.lastUsedAt().toString() : null);
        return out;
    }

    private String generateApiKey() {
        byte[] bytes = new byte[TenantApiCredentialConstants.RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return TenantApiCredentialConstants.TOKEN_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static void validateTenantIdFormat(String tenantId) {
        if (tenantId == null || !TENANT_ID_PATTERN.matcher(tenantId).matches()) {
            throw new IllegalArgumentException(
                    "invalid tenant_id: use lowercase letter first, then [a-z0-9_-], length 2-63");
        }
    }
}
