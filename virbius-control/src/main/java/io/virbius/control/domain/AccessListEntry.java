package io.virbius.control.domain;

import java.time.Instant;

public record AccessListEntry(String value, String remark, Instant createdAt, Instant expiresAt) {}
