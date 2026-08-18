package com.miriyum.domain.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.analytics.config.DashboardSnapshotRetentionScheduleConfig;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.transaction.PlatformTransactionManager;

class DashboardSnapshotRetentionJobTest {

    @Test
    void enabledRetentionRegistersOneHourlyScheduledTask() {
        new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withUserConfiguration(
                        DashboardSnapshotRetentionScheduleConfig.class,
                        DashboardSnapshotRetentionJob.class)
                .withPropertyValues(
                        "miriyum.analytics.snapshot-retention.enabled=true",
                        "miriyum.analytics.snapshot-retention.initial-delay-ms=3600000",
                        "miriyum.analytics.snapshot-retention.fixed-delay-ms=3600000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DashboardSnapshotRetentionJob.class);
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks())
                            .hasSize(1);
                });
    }
}
