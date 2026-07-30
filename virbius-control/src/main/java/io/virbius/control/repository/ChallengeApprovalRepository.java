package io.virbius.control.repository;

import io.virbius.control.domain.ChallengeApprovalRecord;
import java.util.List;

public interface ChallengeApprovalRepository {

    void save(ChallengeApprovalRecord record);

    List<ChallengeApprovalRecord> listByTenantAndStatus(String tenantId, String status, int max);
}
