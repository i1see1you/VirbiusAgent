package io.virbius.control.gateway.artifact;

import java.util.Map;

public record GatewayArtifactPublishResult(
        long artifactRevision,
        String storage,
        String pointerKey,
        String accessListsSha256,
        String accessListsLocator,
        boolean localFallbackWritten,
        Map<String, Object> syncAck) {}
