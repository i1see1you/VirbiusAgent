package io.virbius.control.repository;

import io.virbius.control.domain.TenantApiCredential;
import java.util.List;
import java.util.Optional;

public interface TenantApiCredentialRepository {

    Optional<TenantApiCredential> findActiveByKeyHash(String keyHash);

    List<TenantApiCredential> listByTenant(String tenantId);

    List<TenantApiCredential> listPlatformCredentials();

    void insert(TenantApiCredential credential);

    void revoke(String tenantId, String credentialId);

    void touchLastUsed(String credentialId);
}
