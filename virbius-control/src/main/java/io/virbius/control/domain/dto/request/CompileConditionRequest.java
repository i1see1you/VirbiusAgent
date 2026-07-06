package io.virbius.control.domain.dto.request;

import java.util.Map;

public record CompileConditionRequest(String layer, String runtime, Map<String, Object> condition) {}
