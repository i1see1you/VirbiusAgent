package io.virbius.control.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Counter definition only (window + dimension). Conditions live on cumulative rules. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CumulativeDef(
        String tenantId,
        String cumulativeName,
        String description,
        String dimension,
        String windowKind,
        Integer windowMinutes,
        Integer windowHours,
        String timezone,
        int priority,
        String status,
        String ingestPredicateRuntime,
        String ingestPredicate) {}
