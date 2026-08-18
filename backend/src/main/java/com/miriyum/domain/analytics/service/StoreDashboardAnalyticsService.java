package com.miriyum.domain.analytics.service;

import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.CountMetricResponse;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardMetricDraft;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardSnapshotDraft;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.DashboardSnapshotResponse;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricCompleteness;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricMetadata;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.MetricReasonCode;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.NoShowValue;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.RateValue;
import com.miriyum.domain.analytics.dto.DashboardAnalyticsContracts.WaitingValue;
import com.miriyum.domain.reservation.dto.contract.ReservationAnalyticsSnapshot;
import com.miriyum.domain.reservation.service.ReservationAnalyticsQueryService;
import com.miriyum.domain.reservation.waiting.dto.WaitingAnalyticsSnapshot;
import com.miriyum.domain.reservation.waiting.service.WaitingAnalyticsQueryService;
import com.miriyum.domain.store.dto.contract.StoreDashboardAuthority;
import com.miriyum.domain.store.service.StoreService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Store 권한 뒤 두 source를 독립 호출해 동일 asOf의 dashboard snapshot을 게시한다. */
@Service
public class StoreDashboardAnalyticsService {

    private static final String RESERVATION_DEFINITION = "ANALYTICS-001-v1";
    private static final String CAPACITY_DEFINITION = "ANALYTICS-002-v1";
    private static final String CANCELLATION_DEFINITION = "ANALYTICS-003-v1";
    private static final String WAITING_DEFINITION = "ANALYTICS-004-waiting-v1";
    private static final String NO_SHOW_DEFINITION = "analytics-004-no-show-v2";
    private static final String NO_SHOW_CANDIDATE_DEFINITION =
            "analytics-004-reservation-no-show-candidate-v1";
    private static final String RESERVATION_NO_SHOW_DEFINITION =
            "analytics-004-reservation-no-show-confirmed-v1";
    private static final String WAITING_NO_SHOW_DEFINITION =
            "analytics-004-waiting-no-show-confirmed-v1";

    private final StoreService storeService;
    private final ReservationAnalyticsQueryService reservationSource;
    private final WaitingAnalyticsQueryService waitingSource;
    private final AnalyticsMetricFailureClassifier failureClassifier;
    private final DashboardSnapshotTransactionExecutor executor;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public StoreDashboardAnalyticsService(
            StoreService storeService,
            ReservationAnalyticsQueryService reservationSource,
            WaitingAnalyticsQueryService waitingSource,
            AnalyticsMetricFailureClassifier failureClassifier,
            DashboardSnapshotTransactionExecutor executor,
            Clock clock,
            ObjectMapper objectMapper
    ) {
        this.storeService = storeService;
        this.reservationSource = reservationSource;
        this.waitingSource = waitingSource;
        this.failureClassifier = failureClassifier;
        this.executor = executor;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public DashboardSnapshotResponse getDashboard(long operatorAccountId, long storeId) {
        StoreDashboardAuthority authority =
                storeService.requireDashboardAuthority(operatorAccountId, storeId);
        Instant generatedAt = clock.instant();
        Instant asOf = generatedAt.truncatedTo(ChronoUnit.MINUTES);
        LocalDate businessDate = asOf.atZone(ZoneId.of(authority.timeZoneId())).toLocalDate();
        DashboardSnapshotResponse stored = executor.findStoredSnapshot(
                        storeId, businessDate, asOf, authority.dashboardAuthorityVersion())
                .orElse(null);
        if (stored != null) {
            return stored;
        }

        ReservationAnalyticsSnapshot reservation = null;
        MetricReasonCode reservationFailure = null;
        try {
            reservation = reservationSource.getDashboardSnapshot(storeId, businessDate, asOf);
            requireBoundary(reservation.storeId(), reservation.businessDate(), reservation.asOf(),
                    storeId, businessDate, asOf);
        } catch (RuntimeException failure) {
            reservation = null;
            reservationFailure = failureClassifier.classify(failure);
        }

        WaitingAnalyticsSnapshot waiting = null;
        MetricReasonCode waitingFailure = null;
        try {
            waiting = waitingSource.getDashboardSnapshot(storeId, businessDate, asOf);
            requireBoundary(waiting.storeId(), waiting.businessDate(), waiting.asOf(),
                    storeId, businessDate, asOf);
        } catch (RuntimeException failure) {
            waiting = null;
            waitingFailure = failureClassifier.classify(failure);
        }

        List<DashboardMetricDraft> metrics = List.of(
                reservationCount(reservation, reservationFailure, asOf),
                reservationRate(reservation, reservationFailure, asOf, false),
                reservationRate(reservation, reservationFailure, asOf, true),
                cancellationRate(reservation, reservationFailure, asOf),
                waitingMetric(waiting, waitingFailure, asOf),
                noShowMetric(reservation, reservationFailure, waiting, waitingFailure, asOf));
        return executor.publish(new DashboardSnapshotDraft(
                storeId,
                businessDate,
                authority.timeZoneId(),
                asOf,
                generatedAt,
                authority.dashboardAuthorityVersion(),
                metrics));
    }

    private DashboardMetricDraft reservationCount(
            ReservationAnalyticsSnapshot source,
            MetricReasonCode failure,
            Instant asOf
    ) {
        if (source == null) {
            return unavailable("TODAY_RESERVATION_TEAMS", RESERVATION_DEFINITION,
                    failure, asOf);
        }
        return new DashboardMetricDraft(
                "TODAY_RESERVATION_TEAMS",
                objectMapper.valueToTree(source.todayReservationTeams()),
                complete(RESERVATION_DEFINITION, source.sourceVersion(), asOf,
                        source.dataThrough(), source.inputCheckpoint(), source.corrected()));
    }

    private DashboardMetricDraft reservationRate(
            ReservationAnalyticsSnapshot source,
            MetricReasonCode failure,
            Instant asOf,
            boolean teams
    ) {
        String key = teams ? "TEAM_CAPACITY_UTILIZATION" : "RESERVATION_RATE";
        if (source == null) {
            return unavailable(key, CAPACITY_DEFINITION, failure, asOf);
        }
        long numerator = teams ? source.reservedTeamUnits() : source.reservedPeopleUnits();
        long denominator = teams ? source.offeredTeamUnits() : source.offeredPeopleUnits();
        if (denominator <= 0) {
            return new DashboardMetricDraft(
                    key,
                    null,
                    incomplete(CAPACITY_DEFINITION, source.sourceVersion(), asOf,
                            source.dataThrough(), source.inputCheckpoint(), source.corrected(),
                            MetricCompleteness.UNAVAILABLE,
                            MetricReasonCode.INVALID_DENOMINATOR));
        }
        return new DashboardMetricDraft(
                key,
                objectMapper.valueToTree(rate(numerator, denominator)),
                complete(CAPACITY_DEFINITION, source.sourceVersion(), asOf,
                        source.dataThrough(), source.inputCheckpoint(), source.corrected()));
    }

    private DashboardMetricDraft cancellationRate(
            ReservationAnalyticsSnapshot source,
            MetricReasonCode failure,
            Instant asOf
    ) {
        if (source == null) {
            return unavailable("CANCELLATION_RATE", CANCELLATION_DEFINITION, failure, asOf);
        }
        if (source.everConfirmedTeams() <= 0) {
            return new DashboardMetricDraft(
                    "CANCELLATION_RATE",
                    null,
                    incomplete(CANCELLATION_DEFINITION, source.sourceVersion(), asOf,
                            source.dataThrough(), source.inputCheckpoint(), source.corrected(),
                            MetricCompleteness.UNAVAILABLE,
                            MetricReasonCode.INVALID_DENOMINATOR));
        }
        return new DashboardMetricDraft(
                "CANCELLATION_RATE",
                objectMapper.valueToTree(rate(
                        source.cancelledTeams(), source.everConfirmedTeams())),
                complete(CANCELLATION_DEFINITION, source.sourceVersion(), asOf,
                        source.dataThrough(), source.inputCheckpoint(), source.corrected()));
    }

    private DashboardMetricDraft waitingMetric(
            WaitingAnalyticsSnapshot source,
            MetricReasonCode failure,
            Instant asOf
    ) {
        if (source == null) {
            return unavailable("WAITING_STATUS", WAITING_DEFINITION, failure, asOf);
        }
        WaitingValue value = new WaitingValue(
                source.waitingTeams(), source.calledTeams(), source.waitingPeople(),
                source.calledPeople(), source.longestWaitSeconds());
        return new DashboardMetricDraft(
                "WAITING_STATUS",
                objectMapper.valueToTree(value),
                complete(WAITING_DEFINITION, source.sourceVersion(), asOf,
                        source.dataThrough(), source.inputCheckpoint(), source.corrected()));
    }

    private DashboardMetricDraft noShowMetric(
            ReservationAnalyticsSnapshot reservation,
            MetricReasonCode reservationFailure,
            WaitingAnalyticsSnapshot waiting,
            MetricReasonCode waitingFailure,
            Instant asOf
    ) {
        MetricMetadata missing = incomplete(
                NO_SHOW_CANDIDATE_DEFINITION, 1L, asOf, null, null, false,
                MetricCompleteness.UNAVAILABLE,
                MetricReasonCode.SOURCE_CONTRACT_MISSING);
        CountMetricResponse reservationConfirmed;
        if (reservation == null) {
            MetricMetadata failed = incomplete(
                    RESERVATION_NO_SHOW_DEFINITION, 1L, asOf, null, null, false,
                    completeness(reservationFailure), reservationFailure);
            reservationConfirmed = new CountMetricResponse(null, failed);
        } else {
            MetricMetadata reservationMetadata = complete(
                    RESERVATION_NO_SHOW_DEFINITION, reservation.sourceVersion(), asOf,
                    reservation.dataThrough(), reservation.inputCheckpoint(),
                    reservation.corrected());
            reservationConfirmed = new CountMetricResponse(
                    reservation.confirmedNoShowTeams(), reservationMetadata);
        }

        CountMetricResponse waitingConfirmed;
        if (waiting == null) {
            MetricMetadata failed = incomplete(
                    WAITING_NO_SHOW_DEFINITION, 1L, asOf, null, null, false,
                    completeness(waitingFailure), waitingFailure);
            waitingConfirmed = new CountMetricResponse(null, failed);
        } else {
            MetricMetadata waitingMetadata = complete(
                    WAITING_NO_SHOW_DEFINITION, waiting.sourceVersion(), asOf,
                    waiting.dataThrough(), waiting.inputCheckpoint(), waiting.corrected());
            waitingConfirmed = new CountMetricResponse(
                    waiting.confirmedNoShowTeams(), waitingMetadata);
        }

        long version = Math.addExact(
                reservation == null ? 1L : reservation.sourceVersion(),
                waiting == null ? 1L : waiting.sourceVersion());
        Instant dataThrough = Stream.of(
                        reservation == null ? null : reservation.dataThrough(),
                        waiting == null ? null : waiting.dataThrough())
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        String checkpoint = combinedNoShowCheckpoint(reservation, waiting);
        boolean corrected = (reservation != null && reservation.corrected())
                || (waiting != null && waiting.corrected());
        MetricMetadata top = incomplete(
                NO_SHOW_DEFINITION, version, asOf, dataThrough, checkpoint, corrected,
                MetricCompleteness.PARTIAL,
                MetricReasonCode.SOURCE_CONTRACT_MISSING);
        NoShowValue value = new NoShowValue(
                new CountMetricResponse(null, missing),
                reservationConfirmed,
                waitingConfirmed);
        return new DashboardMetricDraft(
                "NO_SHOW_STATUS", objectMapper.valueToTree(value), top);
    }

    private static String combinedNoShowCheckpoint(
            ReservationAnalyticsSnapshot reservation,
            WaitingAnalyticsSnapshot waiting
    ) {
        if (reservation == null && waiting == null) {
            return null;
        }
        String raw = NO_SHOW_DEFINITION
                + ":reservation=" + (reservation == null
                        ? "unavailable" : reservation.inputCheckpoint())
                + ":waiting=" + (waiting == null
                        ? "unavailable" : waiting.inputCheckpoint());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static DashboardMetricDraft unavailable(
            String key,
            String definition,
            MetricReasonCode reason,
            Instant asOf
    ) {
        return new DashboardMetricDraft(
                key,
                null,
                incomplete(definition, 1L, asOf, null, null, false,
                        completeness(reason), reason));
    }

    private static MetricCompleteness completeness(MetricReasonCode reason) {
        return reason == MetricReasonCode.SOURCE_DELAYED
                ? MetricCompleteness.DELAYED
                : MetricCompleteness.UNAVAILABLE;
    }

    private static MetricMetadata complete(
            String definition,
            long version,
            Instant asOf,
            Instant dataThrough,
            String checkpoint,
            boolean corrected
    ) {
        return new MetricMetadata(
                definition, version, asOf, dataThrough, checkpoint,
                MetricCompleteness.COMPLETE, corrected, null);
    }

    private static MetricMetadata incomplete(
            String definition,
            long version,
            Instant asOf,
            Instant dataThrough,
            String checkpoint,
            boolean corrected,
            MetricCompleteness completeness,
            MetricReasonCode reason
    ) {
        return new MetricMetadata(
                definition, Math.max(1L, version), asOf, dataThrough, checkpoint,
                completeness, corrected, reason);
    }

    private static RateValue rate(long numerator, long denominator) {
        BigDecimal ratio = BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return new RateValue(numerator, denominator, ratio);
    }

    private static void requireBoundary(
            long actualStoreId,
            LocalDate actualDate,
            Instant actualAsOf,
            long expectedStoreId,
            LocalDate expectedDate,
            Instant expectedAsOf
    ) {
        if (actualStoreId != expectedStoreId
                || !expectedDate.equals(actualDate)
                || !expectedAsOf.equals(actualAsOf)) {
            throw new SourceSnapshotBoundaryMismatchException();
        }
    }
}
