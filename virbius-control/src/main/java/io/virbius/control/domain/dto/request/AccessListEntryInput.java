package io.virbius.control.domain.dto.request;

import java.time.Instant;

public record AccessListEntryInput(String value, String remark, Instant expiresAt) {}
