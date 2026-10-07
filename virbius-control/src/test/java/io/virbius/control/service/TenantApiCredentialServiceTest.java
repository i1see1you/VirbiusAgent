package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class TenantApiCredentialServiceTest {

    @Mock
    private TenantApiCredentialRepository repository;

    @Mock
    private TenantRepository tenantRepository;

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void issueStoresHashNotPlaintext() {
        bindIssuer(new ApiKeyPrincipal("issuer-1", "default", ApiRole.TENANT_ADMIN, null));
        when(tenantRepository.exists("default")).thenReturn(true);

        TenantApiCredentialService service = new TenantApiCredentialService(repository, tenantRepository);
        var issued = service.issueForTenant("default", ApiRole.TENANT_VIEWER, "edge");
        assertNotNull(issued.get("api_key"));
        String apiKey = (String) issued.get("api_key");
        assertTrue(apiKey.startsWith(TenantApiCredentialConstants.TOKEN_PREFIX));

        ArgumentCaptor<TenantApiCredential> captor = ArgumentCaptor.forClass(TenantApiCredential.class);
        verify(repository).insert(captor.capture());
        TenantApiCredential stored = captor.getValue();
        assertEquals(ApiKeyHasher.sha256Hex(apiKey), stored.keyHash());
        assertEquals("default", stored.tenantId());
        assertEquals(ApiRole.TENANT_VIEWER, stored.role());
    }

    @Test
    void tenantAdminCannotIssuePlatformRole() {
        bindIssuer(new ApiKeyPrincipal("issuer-1", "default", ApiRole.TENANT_ADMIN, null));
        when(tenantRepository.exists("default")).thenReturn(true);

        TenantApiCredentialService service = new TenantApiCredentialService(repository, tenantRepository);
        assertThrows(BusinessException.class, () -> service.issueForTenant("default", ApiRole.PLATFORM_ADMIN, null));
    }

    @Test
    void revokeMissingCredentialThrows() {
        bindIssuer(new ApiKeyPrincipal("issuer-1", "default", ApiRole.TENANT_ADMIN, null));
        when(repository.listByTenant("default")).thenReturn(List.of());

        TenantApiCredentialService service = new TenantApiCredentialService(repository, tenantRepository);
        assertThrows(ResourceNotFoundException.class, () -> service.revoke("default", "missing"));
    }

    @Test
    void findActiveByTokenMatchesHash() {
        TenantApiCredentialService service = new TenantApiCredentialService(repository, tenantRepository);
        String apiKey = "vrb_tk_test_key";
        String hash = ApiKeyHasher.sha256Hex(apiKey);
        TenantApiCredential cred = new TenantApiCredential(
                "id-1",
                "default",
                ApiRole.TENANT_VIEWER,
                hash,
                "vrb_tk_test",
                null,
                TenantApiCredential.STATUS_ACTIVE,
                "seed",
                Instant.now(),
                null,
                null);
        when(repository.findActiveByKeyHash(hash)).thenReturn(Optional.of(cred));

        assertTrue(service.findActiveByToken(apiKey).isPresent());
    }

    @Test
    void issueWithPepperStoresHmacNotSha256() {
        bindIssuer(new ApiKeyPrincipal("issuer-1", "default", ApiRole.TENANT_ADMIN, null));
        when(tenantRepository.exists("default")).thenReturn(true);
        String pepper = "unit-test-pepper-0123456789abcdef0123456789abcdef";

        TenantApiCredentialService service =
                new TenantApiCredentialService(repository, tenantRepository, pepper);
        var issued = service.issueForTenant("default", ApiRole.TENANT_VIEWER, "edge");
        String apiKey = (String) issued.get("api_key");

        ArgumentCaptor<TenantApiCredential> captor = ArgumentCaptor.forClass(TenantApiCredential.class);
        verify(repository).insert(captor.capture());
        TenantApiCredential stored = captor.getValue();
        assertEquals(ApiKeyHasher.hmacSha256Hex(pepper, apiKey), stored.keyHash());
        assertEquals(TenantApiCredentialConstants.HASH_ALGO_HMAC, stored.hashAlgo());
    }

    @Test
    void legacyKeyUpgradesToHmacOnFirstUse() {
        String pepper = "unit-test-pepper-0123456789abcdef0123456789abcdef";
        TenantApiCredentialService service =
                new TenantApiCredentialService(repository, tenantRepository, pepper);
        String apiKey = "vrb_tk_legacy_key";
        String legacyHash = ApiKeyHasher.sha256Hex(apiKey);
        TenantApiCredential legacyRow = new TenantApiCredential(
                "id-legacy",
                "default",
                ApiRole.TENANT_VIEWER,
                legacyHash,
                "vrb_tk_leg",
                null,
                TenantApiCredential.STATUS_ACTIVE,
                "seed",
                Instant.now(),
                null,
                null,
                TenantApiCredentialConstants.HASH_ALGO_LEGACY);

        // HMAC lookup misses; legacy lookup hits → row is upgraded in place.
        when(repository.findActiveByKeyHash(ApiKeyHasher.hmacSha256Hex(pepper, apiKey)))
                .thenReturn(Optional.empty());
        when(repository.findActiveByKeyHash(legacyHash)).thenReturn(Optional.of(legacyRow));
        when(repository.updateKeyHash(
                eq("id-legacy"),
                eq(ApiKeyHasher.hmacSha256Hex(pepper, apiKey)),
                eq(TenantApiCredentialConstants.HASH_ALGO_HMAC)))
                .thenReturn(1);

        var found = service.findActiveByToken(apiKey);
        assertTrue(found.isPresent());
        verify(repository).updateKeyHash(
                eq("id-legacy"),
                eq(ApiKeyHasher.hmacSha256Hex(pepper, apiKey)),
                eq(TenantApiCredentialConstants.HASH_ALGO_HMAC));
    }

    @Test
    void alreadyUpgradedKeySkipsSecondUpgrade() {
        String pepper = "unit-test-pepper-0123456789abcdef0123456789abcdef";
        TenantApiCredentialService service =
                new TenantApiCredentialService(repository, tenantRepository, pepper);
        String apiKey = "vrb_tk_hmac_key";
        TenantApiCredential hmacRow = new TenantApiCredential(
                "id-hmac",
                "default",
                ApiRole.TENANT_VIEWER,
                ApiKeyHasher.hmacSha256Hex(pepper, apiKey),
                "vrb_tk_hma",
                null,
                TenantApiCredential.STATUS_ACTIVE,
                "seed",
                Instant.now(),
                null,
                null,
                TenantApiCredentialConstants.HASH_ALGO_HMAC);
        when(repository.findActiveByKeyHash(ApiKeyHasher.hmacSha256Hex(pepper, apiKey)))
                .thenReturn(Optional.of(hmacRow));

        assertTrue(service.findActiveByToken(apiKey).isPresent());
        verify(repository, never()).updateKeyHash(anyString(), anyString(), anyString());
    }

    private static void bindIssuer(ApiKeyPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ApiKeyAuthContext.set(request, principal);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
