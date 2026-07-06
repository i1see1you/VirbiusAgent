package io.virbius.control.domain.dto.request;

public record ValidateScriptRequest(String layer, String runtime, Object body) {}
