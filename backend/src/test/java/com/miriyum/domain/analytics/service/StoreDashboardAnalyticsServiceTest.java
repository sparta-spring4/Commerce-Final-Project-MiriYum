package com.miriyum.domain.analytics.service;

import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness.COMPLETE;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness.PARTIAL;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness.UNAVAILABLE;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricReasonCode.SOURCE_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardMetricDraft;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardSnapshotDraft;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardSnapshotResponse;
import com.miriyum.domain.reservation.dto.contract.ReservationAnalyticsSnapshot;
import com.miriyum.domain.reservation.service.ReservationAnalyticsQueryService;
import com.miriyum.domain.reservation.waiting.dto.WaitingAnalyticsSnapshot;
import com.miriyum.domain.reservation.waiting.service.WaitingAnalyticsQueryService;
import com.miriyum.domain.store.dto.contract.StoreDashboardAuthority;
import com.miriyum.domain.store.service.StoreService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class StoreDashboardAnalyticsServiceTest {

    private static final long STORE_ID = 17L;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 16);
    private static final Instant AS_OF = Instant.parse("2026-08-16T09:00:00Z");

    @Mock StoreService storeService;
    @Mock ReservationAnalyticsQueryService reservationSource;
    @Mock WaitingAnalyticsQueryService waitingSource;
    @Mock DashboardSnapshotTransactionExecutor executor;

    private final AtomicReference<DashboardSnapshotDraft> published = new AtomicReference<>();
    private StoreDashboardAnalyticsService service;

    @BeforeEach
    void setUp() {
        given(storeService.requireDashboardAuthority(41L, STORE_ID))
                .willReturn(new StoreDashboardAuthority(STORE_ID, "Asia/Seoul", 1L));
        given(waitingSource.getDashboardSnapshot(STORE_ID, DATE, AS_OF))
                .willReturn(waitingSnapshot());
        given(executor.publish(any())).willAnswer(invocation -> {
            published.set(invocation.getArgument(0));
            return null;
        });
        service = new StoreDashboardAnalyticsService(
                storeService, reservationSource, waitingSource,
                new AnalyticsMetricFailureClassifier(), executor,
                Clock.fixed(AS_OF, ZoneOffset.UTC),
                JsonMapper.builder().build());
    }

    @Test
    void everySourceAndMetricUsesTheOneCapturedAsOf() {
        given(reservationSource.getDashboardSnapshot(STORE_ID, DATE, AS_OF))
                .willReturn(reservationSnapshot());

        service.getDashboard(41L, STORE_ID);

        assertThat(published.get().asOf()).isEqualTo(AS_OF);
        assertThat(published.get().metrics())
                .extracting(metric -> metric.metadata().asOf())
                .containsOnly(AS_OF);
    }

    @Test
    void snapshotBoundaryUsesOneMinuteBucketAndPreservesGenerationTime() {
        Instant generatedAt = AS_OF.plusSeconds(42).plusNanos(123_000_000L);
        given(reservationSource.getDashboardSnapshot(STORE_ID, DATE, AS_OF))
                .willReturn(reservationSnapshot());
        service = new StoreDashboardAnalyticsService(
                storeService, reservationSource, waitingSource,
                new AnalyticsMetricFailureClassifier(), executor,
                Clock.fixed(generatedAt, ZoneOffset.UTC),
                JsonMapper.builder().build());

        service.getDashboard(41L, STORE_ID);

        assertThat(published.get().asOf()).isEqualTo(AS_OF);
        assertThat(published.get().generatedAt()).isEqualTo(generatedAt);
        assertThat(published.get().metrics())
                .extracting(metric -> metric.metadata().asOf())
                .containsOnly(AS_OF);
    }

    @Test
    void storedCanonicalSnapshotReturnsBeforeCallingSources() {
        reset(waitingSource, executor);
        DashboardSnapshotResponse stored = org.mockito.Mockito.mock(
                DashboardSnapshotResponse.class);
        given(executor.findStoredSnapshot(STORE_ID, DATE, AS_OF, 1L))
                .willReturn(Optional.of(stored));

        DashboardSnapshotResponse result = service.getDashboard(41L, STORE_ID);

        assertThat(result).isSameAs(stored);
        verify(reservationSource, never()).getDashboardSnapshot(STORE_ID, DATE, AS_OF);
        verify(waitingSource, never()).getDashboardSnapshot(STORE_ID, DATE, AS_OF);
        verify(executor, never()).publish(any());
    }

    @Test
    void reservationFailureDoesNotOverwriteWaitingSuccess() {
        given(reservationSource.getDashboardSnapshot(STORE_ID, DATE, AS_OF))
                .willThrow(new DataAccessResourceFailureException("reservation down"));

        service.getDashboard(41L, STORE_ID);

        Map<String, DashboardMetricDraft> metrics = published.get().metrics().stream()
                .collect(Collectors.toMap(DashboardMetricDraft::metricKey, Function.identity()));
        assertThat(metrics.get("TODAY_RESERVATION_TEAMS").metadata().completeness())
                .isEqualTo(UNAVAILABLE);
        assertThat(metrics.get("WAITING_STATUS").metadata().completeness())
                .isEqualTo(COMPLETE);
        assertThat(metrics.get("NO_SHOW_STATUS").value()
                .path("waitingConfirmed").path("value").longValue()).isEqualTo(2L);
        assertThat(metrics.get("NO_SHOW_STATUS").value()
                .path("reservationConfirmed").path("value").isNull()).isTrue();
    }

    @Test
    void reservationBoundaryMismatchDoesNotOverwriteWaitingSuccess() {
        given(reservationSource.getDashboardSnapshot(STORE_ID, DATE, AS_OF))
                .willReturn(reservationSnapshot(AS_OF.plusSeconds(60)));

        service.getDashboard(41L, STORE_ID);

        DashboardMetricDraft reservation = metric("TODAY_RESERVATION_TEAMS");
        assertThat(reservation.metadata().completeness()).isEqualTo(UNAVAILABLE);
        assertThat(reservation.metadata().reasonCode()).isEqualTo(SOURCE_FAILED);
        assertThat(metric("WAITING_STATUS").metadata().completeness()).isEqualTo(COMPLETE);
        DashboardMetricDraft noShow = metric("NO_SHOW_STATUS");
        assertThat(noShow.value().path("reservationConfirmed").path("value").isNull())
                .isTrue();
        assertThat(noShow.value().path("waitingConfirmed").path("value").longValue())
                .isEqualTo(2L);
    }

    @Test
    void composesConfirmedSourcesWithoutPretendingCandidateExists() {
        given(reservationSource.getDashboardSnapshot(STORE_ID, DATE, AS_OF))
                .willReturn(reservationSnapshot());

        service.getDashboard(41L, STORE_ID);

        DashboardMetricDraft noShow = metric("NO_SHOW_STATUS");
        assertThat(noShow.value().path("reservationCandidate").path("value").isNull()).isTrue();
        assertThat(noShow.value().path("reservationConfirmed").path("value").longValue())
                .isEqualTo(1L);
        assertThat(noShow.value().path("reservationConfirmed").path("completeness").textValue())
                .isEqualTo("COMPLETE");
        assertThat(noShow.value().path("waitingConfirmed").path("value").longValue())
                .isEqualTo(2L);
        assertThat(noShow.metadata().definitionVersion()).isEqualTo("analytics-004-no-show-v2");
        assertThat(noShow.metadata().completeness()).isEqualTo(PARTIAL);
        assertThat(noShow.metadata().aggregationVersion()).isEqualTo(9L);
        assertThat(noShow.metadata().dataThrough()).isEqualTo(AS_OF.minusSeconds(1));
        assertThat(noShow.metadata().inputCheckpoint())
                .isEqualTo("2fcd6f736e634ca3427ac1877c6c7c229d94fd445082e1a668384038b043ed2e");
        assertThat(noShow.metadata().corrected()).isFalse();
    }

    @Test
    void waitingFailureDoesNotHideReservationConfirmed() {
        given(reservationSource.getDashboardSnapshot(STORE_ID, DATE, AS_OF))
                .willReturn(reservationSnapshot());
        given(waitingSource.getDashboardSnapshot(STORE_ID, DATE, AS_OF))
                .willThrow(new DataAccessResourceFailureException("waiting down"));

        service.getDashboard(41L, STORE_ID);

        DashboardMetricDraft noShow = metric("NO_SHOW_STATUS");
        assertThat(noShow.value().path("reservationConfirmed").path("value").longValue())
                .isEqualTo(1L);
        assertThat(noShow.value().path("reservationConfirmed").path("completeness").textValue())
                .isEqualTo("COMPLETE");
        assertThat(noShow.value().path("waitingConfirmed").path("value").isNull()).isTrue();
        assertThat(noShow.value().path("waitingConfirmed").path("completeness").textValue())
                .isEqualTo("UNAVAILABLE");
    }

    @Test
    void waitingBoundaryMismatchDoesNotHideReservationSuccess() {
        given(reservationSource.getDashboardSnapshot(STORE_ID, DATE, AS_OF))
                .willReturn(reservationSnapshot());
        given(waitingSource.getDashboardSnapshot(STORE_ID, DATE, AS_OF))
                .willReturn(new WaitingAnalyticsSnapshot(
                        STORE_ID + 1, DATE, AS_OF, 1, 0, 3, 0, 1800L, 2,
                        "b".repeat(64), AS_OF.minusSeconds(1), 9, false));

        service.getDashboard(41L, STORE_ID);

        assertThat(metric("TODAY_RESERVATION_TEAMS").metadata().completeness())
                .isEqualTo(COMPLETE);
        DashboardMetricDraft waiting = metric("WAITING_STATUS");
        assertThat(waiting.metadata().completeness()).isEqualTo(UNAVAILABLE);
        assertThat(waiting.metadata().reasonCode()).isEqualTo(SOURCE_FAILED);
        DashboardMetricDraft noShow = metric("NO_SHOW_STATUS");
        assertThat(noShow.value().path("reservationConfirmed").path("value").longValue())
                .isEqualTo(1L);
        assertThat(noShow.value().path("waitingConfirmed").path("value").isNull()).isTrue();
    }

    private DashboardMetricDraft metric(String key) {
        return published.get().metrics().stream()
                .filter(metric -> metric.metricKey().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static ReservationAnalyticsSnapshot reservationSnapshot() {
        return reservationSnapshot(AS_OF);
    }

    private static ReservationAnalyticsSnapshot reservationSnapshot(Instant asOf) {
        return new ReservationAnalyticsSnapshot(
                STORE_ID, DATE, asOf, 4, 6, 10, 3, 5, 1, 4, 1,
                "a".repeat(64), asOf.minusSeconds(2), 7, false);
    }

    private static WaitingAnalyticsSnapshot waitingSnapshot() {
        return waitingSnapshot(AS_OF);
    }

    private static WaitingAnalyticsSnapshot waitingSnapshot(Instant asOf) {
        return new WaitingAnalyticsSnapshot(
                STORE_ID, DATE, asOf, 1, 0, 3, 0, 1800L, 2,
                "b".repeat(64), asOf.minusSeconds(1), 9, false);
    }
}
