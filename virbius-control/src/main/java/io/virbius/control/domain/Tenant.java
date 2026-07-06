package io.virbius.control.domain;

import java.time.Instant;

public record Tenant(String tenantId, String name, Instant createdAt) {}
