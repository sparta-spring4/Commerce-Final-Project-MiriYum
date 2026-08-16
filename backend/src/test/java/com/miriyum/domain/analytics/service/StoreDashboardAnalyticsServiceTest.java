package com.miriyum.domain.analytics.service;

import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness.COMPLETE;
import static com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardMetricDraft;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardSnapshotDraft;
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
    }

    private static ReservationAnalyticsSnapshot reservationSnapshot() {
        return new ReservationAnalyticsSnapshot(
                STORE_ID, DATE, AS_OF, 4, 6, 10, 3, 5, 1, 4,
                "a".repeat(64), AS_OF.minusSeconds(2), 7, false);
    }

    private static WaitingAnalyticsSnapshot waitingSnapshot() {
        return new WaitingAnalyticsSnapshot(
                STORE_ID, DATE, AS_OF, 1, 0, 3, 0, 1800L, 2,
                "b".repeat(64), AS_OF.minusSeconds(1), 9, false);
    }
}
