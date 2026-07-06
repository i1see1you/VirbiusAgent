package io.virbius.control.domain;

import java.time.Instant;

public record EdgeArtifactMeta(
        String tenantId, String appId, String pool, long artifactRevision, String contentSha256, Instant publishedAt) {

    public EdgeArtifactMeta withPool(String pool) {
        return new EdgeArtifactMeta(tenantId, appId, pool, artifactRevision, contentSha256, publishedAt);
    }
}
