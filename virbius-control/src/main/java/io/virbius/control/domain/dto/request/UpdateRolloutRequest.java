package io.virbius.control.domain.dto.request;

public record UpdateRolloutRequest(
        String rolloutState, Integer canaryPercent, Boolean force, String comment) {}
