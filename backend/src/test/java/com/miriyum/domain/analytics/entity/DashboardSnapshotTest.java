package com.miriyum.domain.analytics.entity;

import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness.COMPLETE;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness.UNAVAILABLE;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricReasonCode.SOURCE_FAILED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardMetricDraft;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardSnapshotDraft;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricMetadata;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DashboardSnapshotTest {

    private static final Instant AS_OF = Instant.parse("2026-08-16T09:00:00Z");

    @Test
    void snapshotRejectsMetricWithDifferentAsOf() {
        DashboardMetricDraft metric = new DashboardMetricDraft(
                "TODAY_RESERVATION_TEAMS",
                JsonMapper.builder().build().getNodeFactory().numberNode(3),
                metadata(AS_OF.plusSeconds(1)));
        DashboardSnapshotDraft draft = new DashboardSnapshotDraft(
                17L, LocalDate.of(2026, 8, 16), "Asia/Seoul", AS_OF,
                AS_OF.plusSeconds(1), 1L, List.of(metric));

        assertThatThrownBy(() -> DashboardSnapshot.create(draft))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unavailableMetricRequiresNullValueAndReason() {
        assertThatThrownBy(() -> new DashboardMetricDraft(
                "TODAY_RESERVATION_TEAMS",
                JsonMapper.builder().build().getNodeFactory().numberNode(0),
                new MetricMetadata(
                        "ANALYTICS-001-v1", 1L, AS_OF, null, null,
                        UNAVAILABLE, false, SOURCE_FAILED)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MetricMetadata metadata(Instant asOf) {
        return new MetricMetadata(
                "ANALYTICS-001-v1", 1L, asOf, asOf, "checkpoint",
                COMPLETE, false, null);
    }
}
