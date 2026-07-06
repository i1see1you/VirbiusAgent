package io.virbius.control.domain.dto.request;

public record UpdateRuntimeRequest(String enforceMode, Integer canaryPercent) {}