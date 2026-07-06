package io.virbius.control.security;

public record ApiKeyPrincipal(
        String credentialId, String tenantId, ApiRole role, String label) {}
