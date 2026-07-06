package io.virbius.control.repository;

import io.virbius.control.domain.CumulativeDef;
import java.util.List;
import java.util.Optional;

public interface CumulativeRepository {

    List<CumulativeDef> list(String tenantId, String status);

    Optional<CumulativeDef> get(String tenantId, String cumulativeName);

    void upsert(CumulativeDef def);

    void delete(String tenantId, String cumulativeName);
}
