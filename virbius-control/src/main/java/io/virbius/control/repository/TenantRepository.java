package io.virbius.control.repository;

import io.virbius.control.domain.Tenant;
import java.util.List;
import java.util.Optional;

public interface TenantRepository {

    List<Tenant> listAll();

    Optional<Tenant> findById(String tenantId);

    boolean exists(String tenantId);

    void insert(Tenant tenant);

    void updateName(String tenantId, String name);
}
