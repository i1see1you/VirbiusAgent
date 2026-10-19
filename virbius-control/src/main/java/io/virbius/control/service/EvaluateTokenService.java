package io.virbius.control.service;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.domain.AgentLicense;
import io.virbius.control.repository.EvaluateTokenRepository;
import io.virbius.control.repository.EvaluateTokenRepository.IssuedToken;
import io.virbius.control.repository.EvaluateTokenRepository.IssuerKey;
import io.virbius.control.repository.LicenseRepository;
import io.virbius.control.security.EvaluateTokenSigner;
import io.virbius.control.security.EvaluateTokenSigner.TokenRequest;
import io.virbius.control.security.EvaluateTokenSigner.VerifiedToken;
import io.virbius.control.security.LicenseSigner;
import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * P0-min evaluate-token service: control is the SINGLE issuer of short-lived
 * evaluate access tokens.
 *
 * <ul>
 *   <li>Exchange: a caller presents its long-lived License JWT once and receives
 *       a 15-minute capability-scoped token ({@code aud=evaluate}); the License
 *       JWT never travels per-request afterwards.</li>
 *   <li>Service tokens: per-purpose cross-tenant tokens for internal facades
 *       (simulate) and the gateway.</li>
 *   <li>Revocation: agent-level (license revoke) and jti-level (single token)
 *       — both propagate to the engine via {@link EvaluateRevocationPublisher}.</li>
 *   <li>Issuers: the platform signing key is generated on first boot, stored
 *       encrypted, and its public PEM is published to Redis for the engine.</li>
 * </ul>
 */
@Service
public class EvaluateTokenService {

    private static final Logger log = LoggerFactory.getLogger(EvaluateTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EvaluateTokenRepository repo;
    private final LicenseRepository licenseRepo;
    private final LicenseSigner licenseSigner;
    private final EvaluateTokenSigner tokenSigner;
    private final EvaluateRevocationPublisher publisher;

    private final long ttlSeconds;
    private final long ledgerRetentionHours;
    private final int exchangeRateLimitPerMinute;

    /** Best-effort in-memory rate limit for the exchange endpoint (per license). */
    private final ConcurrentHashMap<String, int[]> exchangeCounters = new ConcurrentHashMap<>();

    public EvaluateTokenService(
            EvaluateTokenRepository repo,
            LicenseRepository licenseRepo,
            LicenseSigner licenseSigner,
            EvaluateTokenSigner tokenSigner,
            EvaluateRevocationPublisher publisher,
            @Value("${virbius.evaluate-token.ttl-seconds:900}") long ttlSeconds,
            @Value("${virbius.evaluate-token.ledger-retention-hours:24}") long ledgerRetentionHours,
            @Value("${virbius.evaluate-token.exchange.rate-limit-per-license-per-min:10}")
            int exchangeRateLimit) {
        this.repo = repo;
        this.licenseRepo = licenseRepo;
        this.licenseSigner = licenseSigner;
        this.tokenSigner = tokenSigner;
        this.publisher = publisher;
        this.ttlSeconds = ttlSeconds;
        this.ledgerRetentionHours = ledgerRetentionHours;
        this.exchangeRateLimitPerMinute = exchangeRateLimit;
    }

    /** Load or generate the platform signing key, then publish the issuer set. */
    @PostConstruct
    public void init() {
        try {
            Optional<IssuerKey> existing = repo.findActiveIssuer();
            String publicKeyPem;
            byte[] rawPrivate;
            String kid;
            if (existing.isPresent()) {
                kid = existing.get().kid();
                publicKeyPem = existing.get().publicKeyPem();
                rawPrivate = licenseSigner.decryptPrivateKey(existing.get().encryptedPrivateKey());
                log.info("evaluate-token signer loaded from registry (kid={})", kid);
            } else {
                LicenseSigner.KeyPairResult kp = licenseSigner.generateKeyPair();
                kid = EvaluateTokenSigner.newKid();
                publicKeyPem = kp.publicKeyPem();
                rawPrivate = licenseSigner.decryptPrivateKey(kp.encryptedPrivateKey());
                try {
                    repo.saveIssuer(new IssuerKey(kid, "control", publicKeyPem,
                            kp.encryptedPrivateKey(), "active"));
                    log.info("evaluate-token signer generated and registered (kid={})", kid);
                } catch (org.springframework.dao.DuplicateKeyException raced) {
                    // Two control replicas cold-started with no active issuer and each
                    // generated a key. The unique active_guard index (V19) makes the
                    // second insert fail; the loser loads and activates the winner's
                    // key so every replica signs with the same kid.
                    IssuerKey winner = repo.findActiveIssuer().orElseThrow(
                            () -> new IllegalStateException(
                                    "evaluate-token issuer race lost but no active issuer found", raced));
                    kid = winner.kid();
                    publicKeyPem = winner.publicKeyPem();
                    rawPrivate = licenseSigner.decryptPrivateKey(winner.encryptedPrivateKey());
                    log.info("evaluate-token issuer race lost, loaded winner (kid={})", kid);
                }
            }
            tokenSigner.activate(kid, publicKeyPem, rawPrivate);
            publishIssuers();
        } catch (Exception e) {
            // Fail fast: without a signing key the exchange endpoint cannot work.
            throw new IllegalStateException("evaluate-token signer init failed: " + e.getMessage(), e);
        }
    }

    /** Idempotent republish (recovery path when Redis loses the issuer hash). */
    public void publishIssuers() {
        Map<String, String> kidToPem = new LinkedHashMap<>();
        for (IssuerKey key : repo.listIssuersByStatus("active")) {
            kidToPem.put(key.kid(), key.publicKeyPem());
        }
        publisher.publishIssuers(kidToPem);
    }

    public String activePublicKeyPem() {
        return tokenSigner.publicKeyPem();
    }

    public String activeKid() {
        return tokenSigner.kid();
    }

    /**
     * Exchange a License JWT for a short evaluate token (the ONLY per-request
     * credential; the license itself never travels per-request afterwards).
     */
    public Map<String, Object> exchange(String licenseJwt, String sid) {
        Map<String, Object> claims = EvaluateTokenSigner.parsePayload(licenseJwt);
        if (claims == null) {
            throw new BusinessException(401, "license_jwt malformed");
        }
        String tenantId = str(claims.get("tenant_id"));
        String appId = str(claims.get("app_id"));
        String agentAid = str(claims.get("agent_aid"));
        long exp = claims.get("exp") instanceof Number n ? n.longValue() : 0L;
        if (tenantId.isBlank() || appId.isBlank()) {
            throw new BusinessException(401, "license_jwt missing tenant/app claims");
        }
        if (exp > 0 && Instant.now().getEpochSecond() >= exp) {
            throw new BusinessException(401, "license expired");
        }

        // Verify the signature with the tenant's active license public key.
        Optional<String> publicKey = licenseRepo.getActivePublicKey(tenantId);
        if (publicKey.isEmpty()
                || !EvaluateTokenSigner.verifyWithPem(licenseJwt, publicKey.get())) {
            throw new BusinessException(401, "license signature verification failed");
        }

        // The license must still be active (revoked licenses are rejected here;
        // the revocation channel covers already-issued tokens).
        AgentLicense license = licenseRepo.findActiveByAppId(tenantId, appId)
                .orElseThrow(() -> new BusinessException(401,
                        "no active license for app '" + appId + "' in tenant '" + tenantId + "'"));

        if (!exchangeRateLimitAllows(license.getLicenseId())) {
            throw new BusinessException(429, "exchange rate limit exceeded, retry shortly");
        }

        TokenRequest request = new TokenRequest(
                "agent:" + agentAid,
                tenantId,
                appId,
                agentAid,
                license.getLicenseId(),
                sid,
                false,
                license.getAllowedTools() != null ? license.getAllowedTools() : List.of(),
                license.getRiskQuota(),
                license.getToolRateLimit(),
                ttlSeconds);
        String token = tokenSigner.sign(request);

        // Ledger entry (jti-level revocation + issuance audit).
        VerifiedToken parsed = EvaluateTokenSigner.parseUnverified(token);
        repo.saveToken(new IssuedToken(
                parsed.jti(), parsed.subject(), tenantId, appId,
                license.getLicenseId(), sid, Instant.now(), Instant.now().plusSeconds(ttlSeconds)));

        log.info("evaluate token exchanged: tenant={} app={} agent={} sid={} ttl={}s",
                tenantId, appId, agentAid, sid == null ? "-" : sid, ttlSeconds);
        return Map.of(
                "access_token", token,
                "token_type", "Bearer",
                "expires_in", ttlSeconds,
                "agent_aid", agentAid,
                "tenant_id", tenantId,
                "app_id", appId);
    }

    /** Mint a per-purpose service token (internal facades / gateway). No wildcard
     *  outside explicitly allowed purposes; cross-tenant only when requested. */
    public Map<String, Object> mintServiceToken(
            String purpose, boolean crossTenant, List<String> allowedTools, Long ttlOverride) {
        long ttl = ttlOverride != null && ttlOverride > 0 ? ttlOverride : ttlSeconds;
        TokenRequest request = new TokenRequest(
                "service:" + purpose,
                null,
                null,
                null,
                null,
                null,
                crossTenant,
                allowedTools != null ? allowedTools : List.of("*"),
                null,
                null,
                ttl);
        String token = tokenSigner.sign(request);
        VerifiedToken parsed = EvaluateTokenSigner.parseUnverified(token);
        repo.saveToken(new IssuedToken(
                parsed.jti(), parsed.subject(), null, null, null, null,
                Instant.now(), Instant.now().plusSeconds(ttl)));
        log.info("service evaluate token minted: purpose={} cross_tenant={} ttl={}s",
                purpose, crossTenant, ttl);
        return Map.of(
                "access_token", token,
                "token_type", "Bearer",
                "expires_in", ttl,
                "subject", "service:" + purpose);
    }

    /** Revoke a single token by jti (finest granularity; ledger-backed). */
    public void revokeByJti(String jti, String reason) {
        Optional<Instant> expiry = repo.findTokenExpiry(jti);
        if (expiry.isEmpty()) {
            throw new BusinessException(404, "unknown or already-revoked jti: " + jti);
        }
        repo.revokeToken(jti, reason);
        publisher.publish("jti:" + jti, null, jti, reason, null);
        log.info("evaluate token revoked: jti={} reason={}", jti, reason);
    }

    /** Ledger cleanup: drop expired-and-unrevoked rows past the retention window. */
    @Scheduled(fixedDelayString = "PT1H")
    public void purgeExpiredLedger() {
        try {
            Instant cutoff = Instant.now().minusSeconds(ledgerRetentionHours * 3600);
            int deleted = repo.deleteExpiredBefore(cutoff);
            if (deleted > 0) {
                log.info("evaluate-token ledger purge: removed {} rows expired before {}",
                        deleted, cutoff);
            }
        } catch (Exception e) {
            log.warn("evaluate-token ledger purge failed: {}", e.getMessage());
        }
    }

    private boolean exchangeRateLimitAllows(String licenseId) {
        long minute = System.currentTimeMillis() / 60_000L;
        int[] counter = exchangeCounters.computeIfAbsent(licenseId, k -> new int[]{(int) minute, 0});
        synchronized (counter) {
            if (counter[0] != (int) minute) {
                counter[0] = (int) minute;
                counter[1] = 0;
            }
            counter[1]++;
            return counter[1] <= exchangeRateLimitPerMinute;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
