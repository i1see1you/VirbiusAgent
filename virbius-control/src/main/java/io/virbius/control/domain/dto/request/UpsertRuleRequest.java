package io.virbius.control.domain.dto.request;

import java.util.List;
import java.util.Map;

public record UpsertRuleRequest(
        String ruleId,
        String bundleId,
        String layer,
        String runtime,
        String reasonCode,
        Integer riskScore,
        String intentAction,
        Map<String, Object> scope,
        Object body,
        String editorMode,
        Map<String, Object> condition,
        Boolean isAsync,
        String asyncActionConfig) {}