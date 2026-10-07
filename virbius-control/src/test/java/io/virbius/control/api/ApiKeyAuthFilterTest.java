package io.virbius.control.api;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.virbius.control.domain.TenantApiCredential;
import io.virbius.control.security.ApiRole;
import io.virbius.control.service.TenantApiCredentialService;
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
class ApiKeyAuthFilterTest {

    @Mock
    private TenantApiCredentialService credentialService;

    @Mock
    private FilterChain filterChain;

    private static ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Test
    void validViewerKeyAllowsEdgeManifest() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(credentialService, objectMapper(), true);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/edge/tenants/default/apps/beta/manifest");
        request.addHeader("Authorization", "Bearer vrb_tk_dev_viewer_default");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(credentialService.findActiveByToken("vrb_tk_dev_viewer_default"))
                .thenReturn(Optional.of(credential("default", ApiRole.TENANT_VIEWER)));

        filter.doFilterInternal(request, response, filterChain);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void viewerKeyDeniedForAdminWrite() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(credentialService, objectMapper(), true);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PATCH", "/api/v1/admin/tenants/default/rules/r1/rollout");
        request.addHeader("Authorization", "Bearer vrb_tk_dev_viewer_default");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(credentialService.findActiveByToken("vrb_tk_dev_viewer_default"))
                .thenReturn(Optional.of(credential("default", ApiRole.TENANT_VIEWER)));

        filter.doFilterInternal(request, response, filterChain);
        verify(filterChain, never()).doFilter(request, response);
        org.junit.jupiter.api.Assertions.assertEquals(403, response.getStatus());
    }

    @Test
    void nonDevProfileWithAllAuthDisabledRejectsImplicitPrincipal() throws Exception {
        org.springframework.mock.env.MockEnvironment prod = new org.springframework.mock.env.MockEnvironment();
        prod.setActiveProfiles("prod");
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                credentialService, objectMapper(), false, false, null, null, prod);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/admin/tenants/default/rules");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        org.junit.jupiter.api.Assertions.assertEquals(503, response.getStatus());
        org.junit.jupiter.api.Assertions.assertNull(
                request.getAttribute(io.virbius.control.security.ApiKeyAuthContext.REQUEST_ATTR),
                "no implicit platform_admin principal may be granted outside the dev profile");
    }

    @Test
    void devProfileWithAllAuthDisabledStillGrantsDevPrincipal() throws Exception {
        org.springframework.mock.env.MockEnvironment dev = new org.springframework.mock.env.MockEnvironment();
        dev.setActiveProfiles("dev");
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                credentialService, objectMapper(), false, false, null, null, dev);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/admin/tenants/default/rules");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    private static TenantApiCredential credential(String tenantId, ApiRole role) {
        return new TenantApiCredential(
                "cred-1",
                tenantId,
                role,
                "hash",
                "vrb_tk_prefix",
                null,
                TenantApiCredential.STATUS_ACTIVE,
                "seed",
                Instant.now(),
                null,
                null);
    }
}
