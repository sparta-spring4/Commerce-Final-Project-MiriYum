package com.miriyum.domain.analytics.service;

import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricReasonCode.SOURCE_DELAYED;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricReasonCode.SOURCE_FAILED;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricReasonCode.SOURCE_QUARANTINED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

class AnalyticsMetricFailureClassifierTest {

    private final AnalyticsMetricFailureClassifier classifier =
            new AnalyticsMetricFailureClassifier();

    @Test
    void classifiesOnlyKnownSourceFailures() {
        assertThat(classifier.classify(new QueryTimeoutException("late")))
                .isEqualTo(SOURCE_DELAYED);
        assertThat(classifier.classify(new DataIntegrityViolationException("bad projection")))
                .isEqualTo(SOURCE_QUARANTINED);
        assertThat(classifier.classify(new DataAccessResourceFailureException("down")))
                .isEqualTo(SOURCE_FAILED);
        assertThat(classifier.classify(new SourceSnapshotBoundaryMismatchException()))
                .isEqualTo(SOURCE_FAILED);
        assertThatThrownBy(() -> classifier.classify(new IllegalArgumentException("bad dto")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
