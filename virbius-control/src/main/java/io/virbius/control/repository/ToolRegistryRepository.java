package io.virbius.control.repository;

import io.virbius.control.domain.ToolRegistryEntry;
import java.util.List;
import java.util.Optional;

public interface ToolRegistryRepository {

    List<ToolRegistryEntry> list(String tenantId);

    Optional<ToolRegistryEntry> get(String tenantId, String toolName);

    void upsert(ToolRegistryEntry entry);

    void delete(String tenantId, String toolName);
}
