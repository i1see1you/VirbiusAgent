package io.virbius.control.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.virbius.control.domain.TenantApiCredential;
import io.virbius.control.security.ApiKeyAuthContext;
import io.virbius.control.security.ApiKeyPrincipal;
import io.virbius.control.security.ApiRole;
import io.virbius.control.security.JwksJwtVerifier;
import io.virbius.control.security.OperatorJwtProperties;
import io.virbius.control.service.TenantApiCredentialService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class OperatorJwtAuthFilterTest {

    @Mock
    TenantApiCredentialService credentials;

    @Mock
    JwksJwtVerifier jwtVerifier;

    @Mock
    FilterChain chain;

    private final OperatorJwtProperties props = props();

    @Test
    void validJwtSetsPrincipal() throws Exception {
        ApiKeyPrincipal p = new ApiKeyPrincipal("u1", "acme", ApiRole.TENANT_ADMIN, "ops");
        when(jwtVerifier.verify("hdr.payload.sig")).thenReturn(Optional.of(p));
        ApiKeyAuthFilter filter = filter(false, true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/tenants/acme/rules");
        req.addHeader("Authorization", "Bearer hdr.payload.sig");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, chain);
        verify(chain).doFilter(req, res);
        assertEquals(p, req.getAttribute(ApiKeyAuthContext.REQUEST_ATTR));
        verify(credentials, never()).findActiveByToken(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void validApiKeyStillWorksWhenBothEnabled() throws Exception {
        when(credentials.findActiveByToken("vrb_tk_abc"))
                .thenReturn(Optional.of(credential("acme", ApiRole.TENANT_ADMIN)));
        ApiKeyAuthFilter filter = filter(true, true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/tenants/acme/rules");
        req.addHeader("Authorization", "Bearer vrb_tk_abc");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, chain);
        verify(chain).doFilter(req, res);
        verify(jwtVerifier, never()).verify(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void expiredJwtIs401() throws Exception {
        when(jwtVerifier.verify("bad")).thenReturn(Optional.empty());
        ApiKeyAuthFilter filter = filter(false, true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/tenants/acme/rules");
        req.addHeader("Authorization", "Bearer bad");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, chain);
        verify(chain, never()).doFilter(req, res);
        assertEquals(401, res.getStatus());
    }

    @Test
    void cookieIsNeverTreatedAsApiKey() throws Exception {
        ApiKeyAuthFilter filter = filter(true, true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/tenants/acme/rules");
        req.setCookies(new Cookie(ApiKeyAuthFilter.OPERATOR_COOKIE, "vrb_tk_looks_like_key"));
        when(jwtVerifier.verify("vrb_tk_looks_like_key")).thenReturn(Optional.empty());
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, chain);
        verify(credentials, never()).findActiveByToken("vrb_tk_looks_like_key");
        assertEquals(401, res.getStatus());
    }

    @Test
    void tenantAdminJwtForbiddenOnPlatformRoute() throws Exception {
        when(jwtVerifier.verify("tok"))
                .thenReturn(Optional.of(new ApiKeyPrincipal("u1", "acme", ApiRole.TENANT_ADMIN, "ops")));
        ApiKeyAuthFilter filter = filter(false, true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/tenants");
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, chain);
        assertEquals(403, res.getStatus());
    }

    @Test
    void crossTenantJwtForbidden() throws Exception {
        when(jwtVerifier.verify("tok"))
                .thenReturn(Optional.of(new ApiKeyPrincipal("u1", "acme", ApiRole.TENANT_ADMIN, "ops")));
        ApiKeyAuthFilter filter = filter(false, true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/tenants/other/rules");
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, chain);
        assertEquals(403, res.getStatus());
    }

    @Test
    void uiWithoutCookieRedirectsToLogin() throws Exception {
        ApiKeyAuthFilter filter = filter(false, true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ui/");
        req.setScheme("http");
        req.setServerName("127.0.0.1");
        req.setServerPort(8080);
        req.addHeader("Host", "127.0.0.1:8080");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, chain);
        verify(chain, never()).doFilter(req, res);
        assertEquals(302, res.getStatus());
        assertTrue(res.getHeader("Location").startsWith("http://127.0.0.1:8082/login?"));
        assertTrue(res.getHeader("Location").contains("return_uri="));
    }

    @Test
    void apiKeyDoesNotUnlockUi() throws Exception {
        ApiKeyAuthFilter filter = filter(true, true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ui/");
        req.addHeader("Authorization", "Bearer vrb_tk_abc");
        req.addHeader("Host", "127.0.0.1:8080");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, chain);
        verify(chain, never()).doFilter(req, res);
        assertEquals(302, res.getStatus());
        verify(credentials, never()).findActiveByToken(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void uiWithValidCookieProceeds() throws Exception {
        when(jwtVerifier.verify("good-jwt"))
                .thenReturn(Optional.of(new ApiKeyPrincipal("u1", "*", ApiRole.PLATFORM_ADMIN, "admin")));
        ApiKeyAuthFilter filter = filter(false, true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ui/");
        req.setCookies(new Cookie(ApiKeyAuthFilter.OPERATOR_COOKIE, "good-jwt"));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, chain);
        verify(chain).doFilter(req, res);
    }

    @Test
    void bothFlagsOffSkipsUiFilter() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(credentials, new ObjectMapper(), false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ui/");
        assertTrue(filter.shouldNotFilter(req));
    }

    private ApiKeyAuthFilter filter(boolean apiKey, boolean jwt) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return new ApiKeyAuthFilter(credentials, mapper, apiKey, jwt, jwtVerifier, props);
    }

    private static OperatorJwtProperties props() {
        OperatorJwtProperties p = new OperatorJwtProperties();
        p.setEnabled(true);
        p.setLoginUrl("http://127.0.0.1:8082/login");
        return p;
    }

    private static TenantApiCredential credential(String tenantId, ApiRole role) {
        return new TenantApiCredential(
                "cred-1",
                tenantId,
                role,
                "hash",
                "vrb_tk_",
                "lab",
                TenantApiCredential.STATUS_ACTIVE,
                "seed",
                Instant.now(),
                null,
                null);
    }
}
