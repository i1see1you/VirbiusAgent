package io.virbius.control.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApiKeyRoutePolicyTest {

    @Test
    void edgeManifestRequiresViewer() {
        assertEquals(
                ApiRole.TENANT_VIEWER,
                ApiKeyRoutePolicy.requiredRole(
                        "GET", "/api/v1/edge/tenants/default/apps/beta/manifest"));
    }

    @Test
    void adminWriteRequiresAdmin() {
        assertEquals(
                ApiRole.TENANT_ADMIN,
                ApiKeyRoutePolicy.requiredRole(
                        "PATCH", "/api/v1/admin/tenants/default/rules/r1/rollout"));
    }

    @Test
    void adminReadRequiresViewer() {
        assertEquals(
                ApiRole.TENANT_VIEWER,
                ApiKeyRoutePolicy.requiredRole("GET", "/api/v1/admin/tenants/default/lists/deny_ip"));
    }

    @Test
    void tenantManagementRequiresPlatform() {
        assertEquals(ApiRole.PLATFORM_ADMIN, ApiKeyRoutePolicy.requiredRole("POST", "/api/v1/admin/tenants"));
        assertEquals(
                ApiRole.PLATFORM_ADMIN,
                ApiKeyRoutePolicy.requiredRole("GET", "/api/v1/admin/tenants/acme"));
    }

    @Test
    void apiCredentialsRequireAdmin() {
        assertEquals(
                ApiRole.TENANT_ADMIN,
                ApiKeyRoutePolicy.requiredRole(
                        "POST", "/api/v1/admin/tenants/default/api-credentials"));
    }

    @Test
    void gatewaySnapshotRequiresViewer() {
        assertEquals(
                ApiRole.TENANT_VIEWER,
                ApiKeyRoutePolicy.requiredRole(
                        "GET", "/api/v1/gateway/tenants/default/snapshot"));
    }

    @Test
    void gatewayArtifactAdminRequiresAdmin() {
        assertEquals(
                ApiRole.TENANT_ADMIN,
                ApiKeyRoutePolicy.requiredRole(
                        "POST", "/api/v1/admin/tenants/default/gateway-artifacts/refresh"));
    }

    @Test
    void tenantScopeForPlatformAdmin() {
        assertTrue(ApiKeyRoutePolicy.tenantScopeAllowed(ApiRole.PLATFORM_ADMIN, "*", "default"));
    }

    @Test
    void tenantScopeMismatchForViewer() {
        assertFalse(ApiKeyRoutePolicy.tenantScopeAllowed(ApiRole.TENANT_VIEWER, "default", "other"));
    }
}
