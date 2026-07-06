package io.virbius.control.domain;

import java.time.Instant;

public record GatewayArtifactMeta(
        String tenantId,
        long artifactRevision,
        String accessListsSha256,
        String sceneRegistrySha256,
        Instant publishedAt,
        String publishId,
        String trigger,
        String storage) {}
