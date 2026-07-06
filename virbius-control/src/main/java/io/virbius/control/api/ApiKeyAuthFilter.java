package io.virbius.control.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.TenantApiCredential;
import io.virbius.control.security.ApiKeyAuthContext;
import io.virbius.control.security.ApiKeyPrincipal;
import io.virbius.control.security.ApiKeyRoutePolicy;
import io.virbius.control.security.ApiRole;
import io.virbius.control.service.TenantApiCredentialService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final ApiKeyPrincipal DEV_PRINCIPAL =
            new ApiKeyPrincipal("dev", TenantApiCredential.PLATFORM_TENANT, ApiRole.PLATFORM_ADMIN, "dev");

    private final TenantApiCredentialService credentialService;
    private final ObjectMapper objectMapper;
    private final boolean authEnabled;

    public ApiKeyAuthFilter(
            TenantApiCredentialService credentialService,
            ObjectMapper objectMapper,
            @Value("${virbius.security.api-key.enabled:false}") boolean authEnabled) {
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
        this.authEnabled = authEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        if (path.startsWith("/ui")
                || path.startsWith("/actuator")
                || path.startsWith("/api/v1/internal/")
                || path.equals("/api/v1/health")) {
            return true;
        }
        return !path.startsWith("/api/v1/admin/")
                && !path.startsWith("/api/v1/edge/")
                && !path.startsWith("/api/v1/gateway/")
                && !path.startsWith("/api/v1/tenants/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!authEnabled) {
            ApiKeyAuthContext.set(request, DEV_PRINCIPAL);
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();
        ApiRole required = ApiKeyRoutePolicy.requiredRole(method, path);
        String pathTenantId = ApiKeyRoutePolicy.extractPathTenantId(path);

        String rawToken = extractToken(request);
        if (rawToken.isBlank()) {
            reject(request, response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "missing api key");
            return;
        }

        var credentialOpt = credentialService.findActiveByToken(rawToken);
        if (credentialOpt.isEmpty()) {
            reject(request, response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "invalid api key");
            return;
        }

        var credential = credentialOpt.get();
        if (!credential.role().satisfies(required)) {
            reject(request, response, HttpServletResponse.SC_FORBIDDEN, "forbidden", "insufficient role");
            return;
        }
        if (!ApiKeyRoutePolicy.tenantScopeAllowed(
                credential.role(), credential.tenantId(), pathTenantId)) {
            reject(request, response, HttpServletResponse.SC_FORBIDDEN, "forbidden", "tenant scope mismatch");
            return;
        }

        credentialService.touchLastUsed(credential.credentialId());
        ApiKeyAuthContext.set(
                request,
                new ApiKeyPrincipal(
                        credential.credentialId(),
                        credential.tenantId(),
                        credential.role(),
                        credential.label()));
        filterChain.doFilter(request, response);
    }

    static String extractToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        String apiKey = request.getHeader("X-Virbius-Api-Key");
        return apiKey != null ? apiKey.trim() : "";
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String error,
            String message)
            throws IOException {
        if (isDeliveryPath(request.getRequestURI())) {
            writePlainError(response, status, error, message);
        } else {
            writeApiResultError(response, status, message);
        }
    }

    private static boolean isDeliveryPath(String path) {
        return path != null
                && (path.startsWith("/api/v1/edge/") || path.startsWith("/api/v1/gateway/"));
    }

    private static boolean isEdgePath(String path) {
        return isDeliveryPath(path);
    }

    private void writePlainError(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private void writeApiResultError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        int code = status == HttpServletResponse.SC_UNAUTHORIZED ? 401 : 403;
        response.getWriter().write(objectMapper.writeValueAsString(ApiResult.error(code, message)));
    }
}
