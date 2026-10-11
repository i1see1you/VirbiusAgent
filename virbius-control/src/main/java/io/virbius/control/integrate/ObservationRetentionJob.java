package io.virbius.control.integrate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ObservationRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ObservationRetentionJob.class);

    private final JdbcTemplate jdbc;
    private final int retentionDays;

    public ObservationRetentionJob(
            JdbcTemplate jdbc,
            @Value("${integrate.retention-days:14}") int retentionDays) {
        this.jdbc = jdbc;
        this.retentionDays = retentionDays > 0 ? retentionDays : 14;
    }

    @Scheduled(cron = "${integrate.retention.cron:0 0 3 * * *}")
    public void purge() {
        String cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS).toString();
        int deleted = jdbc.update(retentionDeleteSql(), cutoff);
        if (deleted > 0) {
            log.info("tool observation retention: purged {} events older than {}", deleted, cutoff);
        }
    }

    String retentionDeleteSql() {
        return "DELETE FROM " + ToolObservationIngestor.OBSERVATION_TABLE + " WHERE observed_at < ?";
    }
}
