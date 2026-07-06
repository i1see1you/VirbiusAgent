package io.virbius.control.repository;

import io.virbius.control.domain.TenantRolloutPolicy;
import java.util.Optional;

public interface TenantRolloutPolicyRepository {

    TenantRolloutPolicy getOrDefault(String tenantId);

    TenantRolloutPolicy save(TenantRolloutPolicy policy);
}
