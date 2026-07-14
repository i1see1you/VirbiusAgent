package io.virbius.control.audit;

import io.virbius.control.audit.HashChainVerifier.ChainVerificationResult;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HashChainVerifyTask {

    private static final Logger log = LoggerFactory.getLogger(HashChainVerifyTask.class);

    private final HashChainVerifier verifier;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final int batchSize;

    public HashChainVerifyTask(
            HashChainVerifier verifier,
            JdbcTemplate jdbc,
            @Value("${virbius.audit.hash-chain.verify-enabled:true}") boolean enabled,
            @Value("${virbius.audit.hash-chain.verify-batch-size:10000}") int batchSize) {
        this.verifier = verifier;
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${virbius.audit.hash-chain.verify-interval-ms:3600000}")
    public void verifyAllTenants() {
        if (!enabled) {
            return;
        }
        var tenants = jdbc.queryForList(
                "SELECT DISTINCT tenant_id FROM tb_audit_chain_state", String.class);
        for (String tenantId : tenants) {
            try {
                Instant to = Instant.now();
                Instant from = to.minus(7, ChronoUnit.DAYS);
                ChainVerificationResult result = verifier.verify(tenantId, from, to);
                if (result.passed()) {
                    log.info("hash chain verify passed: tenant={}, events={}", tenantId, result.verifiedEvents());
                } else {
                    log.error("hash chain BROKEN: tenant={}, breakSeq={}, reason={}",
                            tenantId, result.breakSeq(), result.reason());
                }
            } catch (Exception e) {
                log.error("hash chain verify failed: tenant={}, error={}", tenantId, e.getMessage());
            }
        }
    }
}
