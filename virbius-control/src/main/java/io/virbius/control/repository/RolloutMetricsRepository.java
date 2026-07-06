package io.virbius.control.repository;

public interface RolloutMetricsRepository {

    long countReview24h(String tenantId, String ruleId);

    long countTotalRequests24h(String tenantId);

    long countBlockInCanary24h(String tenantId, String ruleId);

    /** Sum of daily review counts over baseline window, divided by 7 (missing days = 0). */
    double baseline7dDailyAvgReview(String tenantId, String ruleId);

    /** Distinct calendar days with dry_run metrics in the G4 baseline window. */
    int countBaselineDaysWithData(String tenantId, String ruleId);
}
