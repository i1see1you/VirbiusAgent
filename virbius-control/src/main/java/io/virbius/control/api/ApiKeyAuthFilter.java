package io.virbius.control.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.TenantApiCredential;
import io.virbius.control.security.ApiKeyAuthContext;
import io.virbius.control.security.ApiKeyPrincipal;
import io.virbius.control.security.ApiKeyRoutePolicy;
import io.virbius.control.security.ApiRole;
import io.virbius.control.security.JwksJwtVerifier;
import io.virbius.control.security.OperatorJwtProperties;
import io.virbius.control.service.TenantApiCredentialService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String OPERATOR_COOKIE = "vrb_op";
    public static final String LOGIN_STATE_COOKIE = "vrb_login_state";

    private static final ApiKeyPrincipal DEV_PRINCIPAL =
            new ApiKeyPrincipal("dev", TenantApiCredential.PLATFORM_TENANT, ApiRole.PLATFORM_ADMIN, "dev");

    private final TenantApiCredentialService credentialService;
    private final ObjectMapper objectMapper;
    private final boolean apiKeyEnabled;
    private final boolean operatorJwtEnabled;
    private final JwksJwtVerifier jwtVerifier;
    private final OperatorJwtProperties jwtProperties;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ApiKeyAuthFilter(
            TenantApiCredentialService credentialService,
            ObjectMapper objectMapper,
            @Value("${virbius.security.api-key.enabled:false}") boolean apiKeyEnabled,
            @Value("${virbius.security.operator-jwt.enabled:false}") boolean operatorJwtEnabled,
            ObjectProvider<JwksJwtVerifier> jwtVerifier,
            ObjectProvider<OperatorJwtProperties> jwtProperties) {
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
        this.apiKeyEnabled = apiKeyEnabled;
        this.operatorJwtEnabled = operatorJwtEnabled;
        this.jwtVerifier = jwtVerifier == null ? null : jwtVerifier.getIfAvailable();
        this.jwtProperties = jwtProperties == null ? null : jwtProperties.getIfAvailable();
    }

    /** Test helper: API-key-only, operator JWT off. */
    public ApiKeyAuthFilter(
            TenantApiCredentialService credentialService, ObjectMapper objectMapper, boolean apiKeyEnabled) {
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
        this.apiKeyEnabled = apiKeyEnabled;
        this.operatorJwtEnabled = false;
        this.jwtVerifier = null;
        this.jwtProperties = null;
    }

    public ApiKeyAuthFilter(
            TenantApiCredentialService credentialService,
            ObjectMapper objectMapper,
            boolean apiKeyEnabled,
            boolean operatorJwtEnabled,
            JwksJwtVerifier jwtVerifier,
            OperatorJwtProperties jwtProperties) {
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
        this.apiKeyEnabled = apiKeyEnabled;
        this.operatorJwtEnabled = operatorJwtEnabled;
        this.jwtVerifier = jwtVerifier;
        this.jwtProperties = jwtProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        if (path.startsWith("/ui/callback") || path.equals("/ui/logout")) {
            return true;
        }
        if (path.startsWith("/ui/assets/") || path.startsWith("/ui/@vite")) {
            return true;
        }
        if (path.startsWith("/actuator")
                || path.startsWith("/api/v1/internal/")
                || path.equals("/api/v1/health")) {
            return true;
        }
        if (path.startsWith("/ui")) {
            return !operatorJwtEnabled;
        }
        return !path.startsWith("/api/v1/admin/")
                && !path.startsWith("/api/v1/edge/")
                && !path.startsWith("/api/v1/gateway/")
                && !path.startsWith("/api/v1/tenants/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (isUiPath(path)) {
            handleUi(request, response, filterChain);
            return;
        }

        if (!apiKeyEnabled && !operatorJwtEnabled) {
            ApiKeyAuthContext.set(request, DEV_PRINCIPAL);
            filterChain.doFilter(request, response);
            return;
        }

        String method = request.getMethod();
        ApiRole required = ApiKeyRoutePolicy.requiredRole(method, path);
        String pathTenantId = ApiKeyRoutePolicy.extractPathTenantId(path);

        Authn authn = authenticateApi(request);
        if (authn.principal.isEmpty()) {
            reject(request, response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "missing or invalid credential");
            return;
        }
        ApiKeyPrincipal p = authn.principal.get();
        if (!p.role().satisfies(required)) {
            reject(request, response, HttpServletResponse.SC_FORBIDDEN, "forbidden", "insufficient role");
            return;
        }
        if (!ApiKeyRoutePolicy.tenantScopeAllowed(p.role(), p.tenantId(), pathTenantId)) {
            reject(request, response, HttpServletResponse.SC_FORBIDDEN, "forbidden", "tenant scope mismatch");
            return;
        }
        if (authn.touchCredentialId != null) {
            credentialService.touchLastUsed(authn.touchCredentialId);
        }
        ApiKeyAuthContext.set(request, p);
        filterChain.doFilter(request, response);
    }

    private Authn authenticateApi(HttpServletRequest request) {
        String bearer = extractBearer(request);
        String apiKeyHeader = header(request, "X-Virbius-Api-Key");
        if (isApiKey(bearer) || !apiKeyHeader.isBlank()) {
            if (!apiKeyEnabled) {
                return Authn.none();
            }
            String raw = !apiKeyHeader.isBlank() ? apiKeyHeader : bearer;
            return credentialService
                    .findActiveByToken(raw)
                    .map(c -> new Authn(Optional.of(toPrincipal(c)), c.credentialId()))
                    .orElseGet(Authn::none);
        }
        if (!bearer.isBlank()) {
            if (!operatorJwtEnabled || jwtVerifier == null) {
                return Authn.none();
            }
            return new Authn(jwtVerifier.verify(bearer), null);
        }
        if (operatorJwtEnabled && jwtVerifier != null) {
            return new Authn(jwtVerifier.verify(cookieValue(request, OPERATOR_COOKIE)), null);
        }
        return Authn.none();
    }

    private record Authn(Optional<ApiKeyPrincipal> principal, String touchCredentialId) {
        static Authn none() {
            return new Authn(Optional.empty(), null);
        }
    }

    private void handleUi(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!operatorJwtEnabled) {
            filterChain.doFilter(request, response);
            return;
        }
        if (jwtVerifier == null || jwtProperties == null) {
            redirectToLogin(request, response);
            return;
        }
        Optional<ApiKeyPrincipal> fromCookie = jwtVerifier.verify(cookieValue(request, OPERATOR_COOKIE));
        if (fromCookie.isPresent()) {
            ApiKeyAuthContext.set(request, fromCookie.get());
            filterChain.doFilter(request, response);
            return;
        }
        redirectToLogin(request, response);
    }

    private void redirectToLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String state = newState();
        ResponseCookie stateCookie = ResponseCookie.from(LOGIN_STATE_COOKIE, state)
                .httpOnly(true)
                .path("/")
                .sameSite("Lax")
                .maxAge(300)
                .secure(request.isSecure())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, stateCookie.toString());
        String origin = publicOrigin(request);
        String callback = origin + "/ui/callback";
        // Vite (X-Forwarded-Host) keeps login on the same origin so vrb_login_state is not dropped
        // when Auth lives on another host (localhost vs 127.0.0.1).
        String loginPage = header(request, "X-Forwarded-Host").isBlank()
                ? jwtProperties.getLoginUrl()
                : origin + "/login";
        String login = loginPage
                + "?return_uri="
                + URLEncoder.encode(callback, StandardCharsets.UTF_8)
                + "&state="
                + URLEncoder.encode(state, StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, login);
    }

    static String publicOrigin(HttpServletRequest request) {
        String proto = header(request, "X-Forwarded-Proto");
        String host = header(request, "X-Forwarded-Host");
        if (host.isBlank()) {
            host = request.getHeader(HttpHeaders.HOST);
        }
        if (host == null || host.isBlank()) {
            host = "127.0.0.1:" + request.getServerPort();
        }
        String scheme = proto.isBlank() ? request.getScheme() : proto;
        return scheme + "://" + host;
    }

    private String newState() {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static boolean isUiPath(String path) {
        return path != null && path.startsWith("/ui");
    }

    private static boolean isApiKey(String token) {
        return token != null && token.startsWith("vrb_tk_");
    }

    private ApiKeyPrincipal toPrincipal(TenantApiCredential credential) {
        return new ApiKeyPrincipal(
                credential.credentialId(), credential.tenantId(), credential.role(), credential.label());
    }

    static String extractBearer(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        return "";
    }

    public static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return "";
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null) {
                return c.getValue();
            }
        }
        return "";
    }

    private static String header(HttpServletRequest request, String name) {
        String v = request.getHeader(name);
        return v == null ? "" : v.trim();
    }

    private void reject(
            HttpServletRequest request, HttpServletResponse response, int status, String error, String message)
            throws IOException {
        if (isDeliveryPath(request.getRequestURI())) {
            writePlainError(response, status, error, message);
        } else {
            writeApiResultError(response, status, message);
        }
    }

    private static boolean isDeliveryPath(String path) {
        return path != null && (path.startsWith("/api/v1/edge/") || path.startsWith("/api/v1/gateway/"));
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
