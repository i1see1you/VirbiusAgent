package io.virbius.control.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Storage for evaluate-token issuers (signing keys) and the issuance ledger. */
public interface EvaluateTokenRepository {

    // -- issuer key registry --------------------------------------------------

    record IssuerKey(
            String kid,
            String keyType,
            String publicKeyPem,
            String encryptedPrivateKey,
            String status) {}

    void saveIssuer(IssuerKey key);

    Optional<IssuerKey> findActiveIssuer();

    List<IssuerKey> listIssuersByStatus(String status);

    void retireIssuer(String kid);

    // -- issuance ledger -------------------------------------------------------

    record IssuedToken(
            String jti,
            String subject,
            String tenantId,
            String appId,
            String licenseId,
            String sid,
            Instant issuedAt,
            Instant expiresAt) {}

    void saveToken(IssuedToken token);

    Optional<Instant> findTokenExpiry(String jti);

    void revokeToken(String jti, String reason);

    int deleteExpiredBefore(Instant cutoff);

    long countActive();
}
