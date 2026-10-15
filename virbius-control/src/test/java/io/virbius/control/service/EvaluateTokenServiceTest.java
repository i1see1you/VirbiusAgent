package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.domain.AgentLicense;
import io.virbius.control.repository.EvaluateTokenRepository;
import io.virbius.control.repository.EvaluateTokenRepository.IssuerKey;
import io.virbius.control.repository.LicenseRepository;
import io.virbius.control.security.EvaluateTokenSigner;
import io.virbius.control.security.EvaluateTokenSigner.VerifiedToken;
import io.virbius.control.security.LicenseSigner;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvaluateTokenServiceTest {

    @Mock
    private EvaluateTokenRepository repo;

    @Mock
    private LicenseRepository licenseRepo;

    @Mock
    private EvaluateRevocationPublisher publisher;

    private final LicenseSigner licenseSigner;
    {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles("dev");
        licenseSigner = new LicenseSigner(env);
        org.springframework.test.util.ReflectionTestUtils.setField(
                licenseSigner, "masterKey",
                "unit-test-license-master-key-0123456789abcdef-0123456789abcdef");
    }
    private final EvaluateTokenSigner tokenSigner = new EvaluateTokenSigner();
    private EvaluateTokenService service;

    private KeyPair licenseKeyPair;
    private AgentLicense license;

    @BeforeEach
    void setUp() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            KeyPair platformKey = kpg.generateKeyPair();
            String pubPem = EvaluateTokenSigner.toPublicPem(platformKey.getPublic().getEncoded());
            byte[] rawPriv = extractRaw(platformKey.getPrivate().getEncoded());
            tokenSigner.activate(EvaluateTokenSigner.newKid(), pubPem, rawPriv);

            licenseKeyPair = kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        license = new AgentLicense();
        license.setLicenseId("lic_test1");
        license.setTenantId("default");
        license.setAppId("app-1");
        license.setAgentAid("aid:default:app-1");
        license.setAllowedTools(List.of("read_file", "curl"));
        license.setRiskQuota(80);
        license.setToolRateLimit(50);
        license.setExpiry(Instant.now().plusSeconds(86400));
        license.setIssuedAt(Instant.now());
        license.setStatus("active");

        service = new EvaluateTokenService(
                repo, licenseRepo, licenseSigner, tokenSigner, publisher, 900, 24, 100);

        // init(): registry returns a key that the real signer can use.
        LicenseSigner.KeyPairResult kp = licenseSigner.generateKeyPair();
        lenient().when(repo.findActiveIssuer()).thenReturn(Optional.of(new IssuerKey(
                tokenSigner.kid(), "control", tokenSigner.publicKeyPem(),
                kp.encryptedPrivateKey(), "active")));
        lenient().when(repo.listIssuersByStatus("active")).thenReturn(List.of(new IssuerKey(
                tokenSigner.kid(), "control", tokenSigner.publicKeyPem(),
                kp.encryptedPrivateKey(), "active")));
        service.init();
    }

    private static byte[] extractRaw(byte[] pkcs8) {
        byte[] raw = new byte[32];
        System.arraycopy(pkcs8, pkcs8.length - 32, raw, 0, 32);
        return raw;
    }

    /** Build a license JWT signed with the per-tenant license keypair. */
    private String licenseJwt(long expEpochSeconds) {
        try {
            String header = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9";
            String payloadJson = "{\"app_id\":\"app-1\",\"tenant_id\":\"default\","
                    + "\"agent_aid\":\"aid:default:app-1\",\"allowed_tools\":[\"read_file\",\"curl\"],"
                    + "\"risk_quota\":80,\"tool_rate_limit\":50,\"exp\":" + expEpochSeconds + "}";
            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String message = header + "." + payload;
            java.security.Signature sig = java.security.Signature.getInstance("Ed25519");
            sig.initSign(licenseKeyPair.getPrivate());
            sig.update(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return message + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void exchangeIssuesScopedTokenAndWritesLedger() {
        when(licenseRepo.getActivePublicKey("default"))
                .thenReturn(Optional.of(EvaluateTokenSigner.toPublicPem(
                        licenseKeyPair.getPublic().getEncoded())));
        when(licenseRepo.findActiveByAppId("default", "app-1"))
                .thenReturn(Optional.of(license));

        Map<String, Object> result = service.exchange(licenseJwt(Instant.now().plusSeconds(3600).getEpochSecond()), "sess-1");

        String token = (String) result.get("access_token");
        assertNotNull(token);
        assertEquals(900L, result.get("expires_in"));

        VerifiedToken verified = EvaluateTokenSigner.parseUnverified(token);
        assertNotNull(verified);
        assertEquals("agent:aid:default:app-1", verified.subject());
        assertEquals("default", verified.tenantId());
        assertEquals("app-1", verified.appId());
        assertEquals("sess-1", verified.sid());
        assertEquals(List.of("read_file", "curl"), verified.allowedTools());
        assertEquals(80, verified.riskQuota());
        assertEquals("evaluate", verified.audience());

        verify(repo).saveToken(any());
    }

    @Test
    void exchangeRejectsExpiredLicense() {
        long expired = Instant.now().minusSeconds(60).getEpochSecond();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.exchange(licenseJwt(expired), null));
        assertEquals(401, ex.getCode());
    }

    @Test
    void exchangeRejectsBadSignature() {
        when(licenseRepo.getActivePublicKey("default"))
                .thenReturn(Optional.of("-----BEGIN PUBLIC KEY-----\n"
                        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(
                                new java.security.SecureRandom().generateSeed(44))
                        + "\n-----END PUBLIC KEY-----\n"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.exchange(licenseJwt(Instant.now().plusSeconds(3600).getEpochSecond()), null));
        assertEquals(401, ex.getCode());
    }

    @Test
    void exchangeRejectsRevokedLicense() {
        when(licenseRepo.getActivePublicKey("default"))
                .thenReturn(Optional.of(EvaluateTokenSigner.toPublicPem(
                        licenseKeyPair.getPublic().getEncoded())));
        when(licenseRepo.findActiveByAppId("default", "app-1"))
                .thenReturn(Optional.empty()); // revocation empties the active view
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.exchange(licenseJwt(Instant.now().plusSeconds(3600).getEpochSecond()), null));
        assertEquals(401, ex.getCode());
    }

    @Test
    void serviceTokenIsCrossTenantWithWildcardOnlyWhenRequested() {
        Map<String, Object> cross = service.mintServiceToken("gateway", true, null, null);
        VerifiedToken v = EvaluateTokenSigner.parseUnverified((String) cross.get("access_token"));
        assertNotNull(v);
        assertEquals("service:gateway", v.subject());
        assertEquals(true, v.crossTenant());
        assertEquals(List.of("*"), v.allowedTools());

        Map<String, Object> scoped = service.mintServiceToken("control-simulate", false, List.of("simulate"), null);
        VerifiedToken s = EvaluateTokenSigner.parseUnverified((String) scoped.get("access_token"));
        assertNotNull(s);
        assertEquals(false, s.crossTenant());
        assertEquals(List.of("simulate"), s.allowedTools());
    }

    @Test
    void revokeByJtiPropagatesToEngine() {
        when(repo.findTokenExpiry(anyString())).thenReturn(Optional.of(Instant.now().plusSeconds(600)));
        service.revokeByJti("jti-1", "suspicious");
        verify(repo).revokeToken("jti-1", "suspicious");
        verify(publisher).publish("jti:jti-1", null, "jti-1", "suspicious", null);
    }

    @Test
    void revokeUnknownJtiReturns404() {
        when(repo.findTokenExpiry(anyString())).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.revokeByJti("nope", "x"));
        assertEquals(404, ex.getCode());
    }
}
