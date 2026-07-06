package io.virbius.control.repository;

import io.virbius.control.domain.DeployEvent;
import io.virbius.control.domain.DeployRollout;
import java.util.List;
import java.util.Optional;

public interface DeployRolloutRepository {

    void create(DeployRollout rollout);

    Optional<DeployRollout> get(String deployId);

    Optional<DeployRollout> findActive(String tenantId);

    List<DeployRollout> listByTenant(String tenantId, int limit);

    void updateState(String deployId, String state, int canaryPercent, boolean edgeDeployed);

    void markFinalized(String deployId, String terminalState);

    void recordEvent(DeployEvent event);

    List<DeployEvent> listEvents(String deployId);
}
