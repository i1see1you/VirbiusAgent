package io.virbius.control.domain.dto.request;

public record ValidateScriptRequest(String ruleId, String layer, String runtime, Object body) {}
