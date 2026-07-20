package io.virbius.control.repository;

import io.virbius.control.domain.AgentLicense;
import java.util.List;
import java.util.Optional;

public interface LicenseRepository {

    void save(AgentLicense license, String licenseId);

    Optional<AgentLicense> findActiveByAppId(String tenantId, String appId);

    List<AgentLicense> listByTenant(String tenantId, String status);

    /** Returns the distinct set of app_ids that have been issued a license (any status). */
    List<String> listAppIds(String tenantId);

    void revoke(String licenseId, String revokedBy, String reason);

    void saveKeyPair(String keyId, String tenantId, String publicKeyPem, String encryptedPrivateKey);

    Optional<String> getActivePublicKey(String tenantId);

    Optional<String> getActiveEncryptedPrivateKey(String tenantId);

    void rotateKey(String tenantId, String newKeyId, String newPubPem, String newEncPriv);
}
