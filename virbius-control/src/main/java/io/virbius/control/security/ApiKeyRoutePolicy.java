package io.virbius.control.security;

import io.virbius.control.domain.TenantApiCredential;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ApiKeyRoutePolicy {

    private static final Pattern ADMIN_TENANT_ROOT =
            Pattern.compile("^/api/v1/admin/tenants/([^/]+)$");
    private static final Pattern ADMIN_TENANT_SUB =
            Pattern.compile("^/api/v1/admin/tenants/([^/]+)/.+");
    private static final Pattern LEGACY_TENANT_SUB =
            Pattern.compile("^/api/v1/tenants/([^/]+)(?:/.*)?$");
    private static final Pattern EDGE_TENANT =
            Pattern.compile("^/api/v1/edge/tenants/([^/]+)/.+");
    private static final Pattern GATEWAY_TENANT =
            Pattern.compile("^/api/v1/gateway/tenants/([^/]+)/.+");

    private ApiKeyRoutePolicy() {}

    public static ApiRole requiredRole(String method, String path) {
        if (path == null || path.isBlank()) {
            return ApiRole.PLATFORM_ADMIN;
        }
        String m = method != null ? method.toUpperCase() : "GET";
        if ("/api/v1/admin/tenants".equals(path)) {
            return ApiRole.PLATFORM_ADMIN;
        }
        if (path.startsWith("/api/v1/admin/platform/")) {
            return ApiRole.PLATFORM_ADMIN;
        }
        Matcher root = ADMIN_TENANT_ROOT.matcher(path);
        if (root.matches()) {
            return ApiRole.PLATFORM_ADMIN;
        }
        if (path.contains("/gateway-artifacts")) {
            return ApiRole.TENANT_ADMIN;
        }
        if (path.contains("/api-credentials")) {
            return ApiRole.TENANT_ADMIN;
        }
        if (path.startsWith("/api/v1/edge/") || path.startsWith("/api/v1/gateway/")) {
            return ApiRole.TENANT_VIEWER;
        }
        if (path.startsWith("/api/v1/admin/tenants/") || path.startsWith("/api/v1/tenants/")) {
            if ("GET".equals(m)) {
                return ApiRole.TENANT_VIEWER;
            }
            return ApiRole.TENANT_ADMIN;
        }
        return ApiRole.TENANT_ADMIN;
    }

    public static String extractPathTenantId(String path) {
        if (path == null) {
            return null;
        }
        Matcher edge = EDGE_TENANT.matcher(path);
        if (edge.matches()) {
            return edge.group(1);
        }
        Matcher gateway = GATEWAY_TENANT.matcher(path);
        if (gateway.matches()) {
            return gateway.group(1);
        }
        Matcher adminSub = ADMIN_TENANT_SUB.matcher(path);
        if (adminSub.matches()) {
            return adminSub.group(1);
        }
        Matcher adminRoot = ADMIN_TENANT_ROOT.matcher(path);
        if (adminRoot.matches()) {
            return adminRoot.group(1);
        }
        Matcher legacy = LEGACY_TENANT_SUB.matcher(path);
        if (legacy.matches()) {
            return legacy.group(1);
        }
        return null;
    }

    public static boolean tenantScopeAllowed(ApiRole role, String credentialTenantId, String pathTenantId) {
        if (role == ApiRole.PLATFORM_ADMIN || TenantApiCredential.PLATFORM_TENANT.equals(credentialTenantId)) {
            return true;
        }
        if (pathTenantId == null || pathTenantId.isBlank()) {
            return false;
        }
        return pathTenantId.equals(credentialTenantId);
    }
}
