package io.virbius.control.service;

import io.virbius.control.common.exception.ResourceNotFoundException;
import io.virbius.control.domain.AgentLicense;
import io.virbius.control.repository.LicenseRepository;
import io.virbius.control.config.ControlJedisPools;
import io.virbius.control.security.LicenseSigner;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;

/**
 * Issues, revokes, and manages Runtime Licenses for Agent identities.
 *
 * <p>Licenses are EdDSA-signed JWTs verified by virbius-core at the edge layer.
 * Revocation is propagated via Redis pub/sub so that all edge nodes can update
 * their in-memory revocation list without restart.
 */
@Service
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);
    private static final String REVOCATION_CHANNEL = "virbius:license:revoked";

    private final LicenseRepository repo;
    private final LicenseSigner signer;
    private final Optional<JedisPool> jedisPool;

    public LicenseService(LicenseRepository repo, LicenseSigner signer, ControlJedisPools jedisPools) {
        this.repo = repo;
        this.signer = signer;
        this.jedisPool = jedisPools.pool();
    }

    /**
     * Issue a new License for an Agent.
     *
     * @return the signed JWT and license metadata
     */
    public Map<String, Object> issueLicense(
            String tenantId,
            String appId,
            String agentName,
            List<String> allowedTools,
            int riskQuota,
            int toolRateLimit,
            long expirySeconds,
            String description) {

        // Ensure tenant has a signing key pair
        String encPrivKey = repo.getActiveEncryptedPrivateKey(tenantId)
                .orElseGet(() -> {
                    log.info("no active signing key for tenant {}, generating new key pair", tenantId);
                    LicenseSigner.KeyPairResult kp = signer.generateKeyPair();
                    repo.saveKeyPair(kp.keyId(), tenantId, kp.publicKeyPem(), kp.encryptedPrivateKey());
                    return kp.encryptedPrivateKey();
                });

        byte[] signingKey = signer.decryptPrivateKey(encPrivKey);

        String agentAid = buildAgentAid(tenantId, appId);

        AgentLicense license = new AgentLicense();
        license.setAppId(appId);
        license.setTenantId(tenantId);
        license.setAgentName(agentName);
        license.setDescription(description);
        license.setAgentAid(agentAid);
        license.setAllowedTools(allowedTools);
        license.setRiskQuota(riskQuota);
        license.setToolRateLimit(toolRateLimit);
        license.setExpiry(Instant.now().plusSeconds(expirySeconds));
        license.setIssuedAt(Instant.now());
        license.setStatus("active");

        String jwt = signer.sign(signingKey, license);
        license.setSignature(jwt);

        String licenseId = "lic_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        repo.save(license, licenseId);

        log.info("issued license {} for app {} tenant {} (quota={}, rate={}, tools={})",
                licenseId, appId, tenantId, riskQuota, toolRateLimit, allowedTools);

        return Map.ofEntries(
                Map.entry("license_id", licenseId),
                Map.entry("app_id", appId),
                Map.entry("tenant_id", tenantId),
                Map.entry("agent_name", agentName),
                Map.entry("agent_aid", agentAid),
                Map.entry("jwt", jwt),
                Map.entry("expiry", license.getExpiry().toString()),
                Map.entry("allowed_tools", allowedTools),
                Map.entry("risk_quota", riskQuota),
                Map.entry("tool_rate_limit", toolRateLimit));
    }

    private static String buildAgentAid(String tenantId, String appId) {
        String serial = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return String.format("aid:cn:org:%s:agent:%s-%s", tenantId, appId, serial);
    }

    /**
     * Revoke a License by ID. Propagates via Redis pub/sub.
     */
    public void revokeLicense(String licenseId, String revokedBy, String reason) {
        repo.revoke(licenseId, revokedBy, reason);

        // Publish revocation via Redis pub/sub
        if (jedisPool.isPresent()) {
            try (var jedis = jedisPool.get().getResource()) {
                jedis.publish(REVOCATION_CHANNEL,
                        "{\"license_id\":\"" + licenseId + "\",\"reason\":\"" + reason + "\"}");
            } catch (Exception e) {
                log.warn("failed to publish revocation via Redis: {}", e.getMessage());
            }
        } else {
            log.warn("Redis not configured; revocation not published via pub/sub");
        }

        log.info("revoked license {} by {} reason: {}", licenseId, revokedBy, reason);
    }

    /**
     * Get the active License for an Agent app.
     */
    public AgentLicense getActiveLicense(String tenantId, String appId) {
        return repo.findActiveByAppId(tenantId, appId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "no active license for app " + appId + " in tenant " + tenantId));
    }

    /**
     * List all licenses for a tenant.
     */
    public List<AgentLicense> listLicenses(String tenantId, String status) {
        return repo.listByTenant(tenantId, status);
    }

    /**
     * Get the public key PEM for a tenant (for edge nodes to verify License JWTs).
     */
    public String getPublicKey(String tenantId) {
        return repo.getActivePublicKey(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "no active signing key for tenant " + tenantId));
    }

    /**
     * Rotate the signing key pair for a tenant. Old key becomes 'rotated'.
     */
    public Map<String, Object> rotateKey(String tenantId) {
        LicenseSigner.KeyPairResult kp = signer.generateKeyPair();
        repo.rotateKey(tenantId, kp.keyId(), kp.publicKeyPem(), kp.encryptedPrivateKey());
        log.info("rotated signing key for tenant {}: new keyId={}", tenantId, kp.keyId());
        return Map.of(
                "key_id", kp.keyId(),
                "public_key_pem", kp.publicKeyPem());
    }
}
