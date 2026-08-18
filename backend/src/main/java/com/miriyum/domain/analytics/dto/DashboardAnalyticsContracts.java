package com.miriyum.domain.analytics.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Issue #270의 내부 draft와 외부 응답 타입을 한곳에 고정한다. */
public final class DashboardAnalyticsContracts {

    private DashboardAnalyticsContracts() {
    }

    public enum MetricCompleteness { COMPLETE, DELAYED, PARTIAL, UNAVAILABLE }

    public enum MetricReasonCode {
        SOURCE_CONTRACT_MISSING,
        SOURCE_DELAYED,
        SOURCE_FAILED,
        SOURCE_QUARANTINED,
        INVALID_DENOMINATOR
    }

    public record MetricMetadata(
            String definitionVersion,
            long aggregationVersion,
            Instant asOf,
            Instant dataThrough,
            String inputCheckpoint,
            MetricCompleteness completeness,
            boolean corrected,
            MetricReasonCode reasonCode
    ) {
        public MetricMetadata {
            if (definitionVersion == null || definitionVersion.isBlank()
                    || aggregationVersion <= 0 || asOf == null || completeness == null) {
                throw new IllegalArgumentException("valid metric metadata is required");
            }
            if (dataThrough != null && dataThrough.isAfter(asOf)) {
                throw new IllegalArgumentException("dataThrough must not be after asOf");
            }
            if (inputCheckpoint != null && inputCheckpoint.isBlank()) {
                throw new IllegalArgumentException("inputCheckpoint must be null or non-blank");
            }
            if ((completeness == MetricCompleteness.COMPLETE) != (reasonCode == null)) {
                throw new IllegalArgumentException(
                        "complete metrics have no reason; incomplete metrics require one");
            }
        }
    }

    public record RateValue(long numerator, long denominator, BigDecimal ratio) {
        public RateValue {
            if (numerator < 0 || denominator <= 0 || ratio == null || ratio.signum() < 0) {
                throw new IllegalArgumentException("valid rate value is required");
            }
        }
    }

    public record WaitingValue(
            long waitingTeams,
            long calledTeams,
            long waitingPeople,
            long calledPeople,
            Long longestWaitSeconds
    ) {
        public WaitingValue {
            if (waitingTeams < 0 || calledTeams < 0
                    || waitingPeople < 0 || calledPeople < 0
                    || (longestWaitSeconds != null && longestWaitSeconds < 0)) {
                throw new IllegalArgumentException("valid waiting value is required");
            }
        }
    }

    public record CountMetricResponse(Long value, @JsonUnwrapped MetricMetadata metadata) {
    }

    public record RateMetricResponse(RateValue value, @JsonUnwrapped MetricMetadata metadata) {
    }

    public record WaitingMetricResponse(
            WaitingValue value,
            @JsonUnwrapped MetricMetadata metadata
    ) {
    }

    public record NoShowValue(
            CountMetricResponse reservationCandidate,
            CountMetricResponse reservationConfirmed,
            CountMetricResponse waitingConfirmed
    ) {
    }

    public record NoShowMetricResponse(
            NoShowValue value,
            @JsonUnwrapped MetricMetadata metadata
    ) {
    }

    public record DashboardMetricsResponse(
            CountMetricResponse todayReservationTeams,
            RateMetricResponse reservationRate,
            RateMetricResponse teamCapacityUsageRate,
            RateMetricResponse cancellationRate,
            WaitingMetricResponse waiting,
            NoShowMetricResponse noShow
    ) {
    }

    public record DashboardSnapshotResponse(
            UUID snapshotId,
            String storeId,
            LocalDate businessDate,
            String timeZoneId,
            Instant asOf,
            Instant generatedAt,
            long storeAuthorityVersion,
            DashboardMetricsResponse metrics
    ) {
    }

    public record DashboardMetricDraft(
            String metricKey,
            JsonNode value,
            MetricMetadata metadata
    ) {
        public DashboardMetricDraft {
            if (metricKey == null || metricKey.isBlank() || metadata == null) {
                throw new IllegalArgumentException("metric key and metadata are required");
            }
            if (metadata.completeness() == MetricCompleteness.UNAVAILABLE && value != null) {
                throw new IllegalArgumentException("unavailable metric value must be null");
            }
        }
    }

    public record DashboardSnapshotDraft(
            long storeId,
            LocalDate businessDate,
            String timeZoneId,
            Instant asOf,
            Instant generatedAt,
            long storeAuthorityVersion,
            List<DashboardMetricDraft> metrics
    ) {
        public DashboardSnapshotDraft {
            if (storeId <= 0 || businessDate == null || !"Asia/Seoul".equals(timeZoneId)
                    || asOf == null || generatedAt == null || generatedAt.isBefore(asOf)
                    || storeAuthorityVersion <= 0 || metrics == null) {
                throw new IllegalArgumentException("valid dashboard snapshot draft is required");
            }
            metrics = List.copyOf(metrics);
        }
    }
}
