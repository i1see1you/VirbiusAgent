package io.virbius.control.repository;

import io.virbius.control.domain.EdgeArtifactMeta;
import java.util.List;
import java.util.Optional;

public interface EdgeArtifactMetaRepository {

    Optional<EdgeArtifactMeta> get(String tenantId, String appId, String pool);

    List<EdgeArtifactMeta> listByTenant(String tenantId);

    void save(EdgeArtifactMeta meta);

    void delete(String tenantId, String appId, String pool);
}
