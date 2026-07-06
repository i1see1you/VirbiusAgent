package io.virbius.control.repository;

import io.virbius.control.domain.GatewayArtifactMeta;
import java.util.Optional;

public interface GatewayArtifactMetaRepository {

    Optional<GatewayArtifactMeta> get(String tenantId);

    void save(GatewayArtifactMeta meta);
}
