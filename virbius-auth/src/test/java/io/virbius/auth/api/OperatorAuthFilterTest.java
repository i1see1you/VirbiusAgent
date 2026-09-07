package io.virbius.auth.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.virbius.auth.domain.OperatorUser;
import io.virbius.auth.security.LocalJwtVerifier;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class OperatorAuthFilterTest {

    @Mock
    LocalJwtVerifier verifier;

    @Mock
    FilterChain chain;

    @Test
    void anonymousIs401() throws Exception {
        when(verifier.verify("")).thenReturn(Optional.empty());
        OperatorAuthFilter filter = new OperatorAuthFilter(verifier);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/users");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        assertEquals(401, res.getStatus());
    }

    @Test
    void tenantAdminIs403() throws Exception {
        OperatorUser tenantAdmin = new OperatorUser(
                "u1", "ops", null, "tenant_admin", "acme", OperatorUser.STATUS_ACTIVE, Instant.now());
        when(verifier.verify("tok")).thenReturn(Optional.of(tenantAdmin));
        OperatorAuthFilter filter = new OperatorAuthFilter(verifier);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/users");
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        assertEquals(403, res.getStatus());
    }

    @Test
    void platformAdminProceeds() throws Exception {
        OperatorUser admin = new OperatorUser(
                "u1", "admin", null, "platform_admin", "*", OperatorUser.STATUS_ACTIVE, Instant.now());
        when(verifier.verify("tok")).thenReturn(Optional.of(admin));
        OperatorAuthFilter filter = new OperatorAuthFilter(verifier);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/users");
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
        assertEquals(admin, OperatorAuthContext.get(req));
    }
}
