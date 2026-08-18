package com.miriyum.domain.analytics.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Store 발행 잠금과 분리된 bounded batch로 오래된 dashboard snapshot을 정리한다. */
@Component
@ConditionalOnProperty(
        name = "miriyum.analytics.snapshot-retention.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DashboardSnapshotRetentionJob {

    private static final Duration RETENTION = Duration.ofDays(31);
    private static final int BATCH_SIZE = 1_000;
    private static final int MAX_BATCHES_PER_RUN = 10;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public DashboardSnapshotRetentionJob(
            JdbcTemplate jdbcTemplate,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setTimeout(5);
    }

    @Scheduled(
            fixedDelayString =
                    "${miriyum.analytics.snapshot-retention.fixed-delay-ms:3600000}",
            initialDelayString =
                    "${miriyum.analytics.snapshot-retention.initial-delay-ms:3600000}")
    public void pruneExpiredSnapshots() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                clock.instant().minus(RETENTION), ZoneOffset.UTC);
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            Integer deleted = transactionTemplate.execute(status -> jdbcTemplate.update("""
                    DELETE FROM dashboard_analytics_snapshots
                    WHERE generated_at < ?
                    ORDER BY generated_at, dashboard_snapshot_id
                    LIMIT ?
                    """, cutoff, BATCH_SIZE));
            if (deleted == null || deleted < BATCH_SIZE) {
                return;
            }
        }
    }
}
