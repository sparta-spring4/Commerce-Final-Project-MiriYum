package com.miriyum.domain.platformoperator.adminmonitoring.service;

import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseType.RESERVATION;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseType.WAITING;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LifecycleStatus.CANCELLED;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LifecycleStatus.CHECKED_IN;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LifecycleStatus.COMPLETED;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LifecycleStatus.CONFIRMED;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LifecycleStatus.NO_SHOW;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LifecycleStatus.PENDING;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.ReconciliationStatus.MATCHED;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Source.MENU_HOLD;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Source.RESERVATION_HOLD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringRequests.ListQuery;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseType;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LifecycleStatus;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.ReconciliationStatus;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Source;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AdminMonitoringStatusMapperTest {

    private final AdminMonitoringStatusMapper mapper = new AdminMonitoringStatusMapper();

    @ParameterizedTest
    @MethodSource("sourceStatuses")
    void mapsEveryPrimarySourceStatusWithoutLettingReconciliationOverwriteLifecycle(
            Source source,
            String sourceStatus,
            LifecycleStatus expected
    ) {
        assertThat(mapper.lifecycle(source, sourceStatus)).isEqualTo(expected);
    }

    @Test
    void reservationCheckInEventAdvancesConfirmedReservationToCheckedIn() {
        assertThat(mapper.reservationLifecycle("CONFIRMED", Set.of("CHECKED_IN")))
                .isEqualTo(CHECKED_IN);
    }

    @Test
    void rejectsRangesLongerThanThirtyOneDays() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");

        assertThatThrownBy(() -> query(
                from, from.plus(31, ChronoUnit.DAYS).plusNanos(1), 20,
                Set.of("RESERVATION:CONFIRMED")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnqualifiedOrUnknownSourceStatuses() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");

        assertThatThrownBy(() -> query(from, from.plusSeconds(1), 20, Set.of("CONFIRMED")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query(from, from.plusSeconds(1), 20, Set.of("PAYMENT:CAPTURED")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSourceStatusFiltersThatCannotBelongToTheSelectedCaseTypes() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");

        assertThatThrownBy(() -> new ListQuery(
                null, Set.of(WAITING), Set.of(), Set.of("RESERVATION:CONFIRMED"), Set.of(),
                from, from.plusSeconds(1), 20, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ListQuery(
                null, Set.of(RESERVATION), Set.of(), Set.of("WAITING:CALLED"), Set.of(),
                from, from.plusSeconds(1), 20, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalizesFiltersForStableCursorBinding() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        ListQuery query = new ListQuery(
                "9", Set.of(WAITING, RESERVATION), Set.of(CONFIRMED, PENDING),
                Set.of("WAITING:CALLED", "RESERVATION:CONFIRMED"), Set.of(MATCHED),
                from, from.plusSeconds(1), 40, null);

        assertThat(query.canonicalFilter())
                .isEqualTo("storeId=9\ncaseTypes=RESERVATION,WAITING"
                        + "\nlifecycleStatuses=CONFIRMED,PENDING"
                        + "\nsourceStatuses=RESERVATION:CONFIRMED,WAITING:CALLED"
                        + "\nreconciliationStatuses=MATCHED"
                        + "\nchangedFrom=2026-08-01T00:00:00Z"
                        + "\nchangedTo=2026-08-01T00:00:01Z\nsize=40");
    }

    @Test
    void linkedSourcesCannotDetermineCaseLifecycle() {
        assertThatThrownBy(() -> mapper.lifecycle(MENU_HOLD, "ACTIVE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ListQuery query(Instant from, Instant to, int size, Set<String> sourceStatuses) {
        return new ListQuery(
                null, Set.of(RESERVATION), Set.of(), sourceStatuses,
                Set.of(ReconciliationStatus.MATCHED), from, to, size, null);
    }

    private static Stream<Arguments> sourceStatuses() {
        return Stream.of(
                Arguments.of(RESERVATION_HOLD, "ACTIVE", PENDING),
                Arguments.of(RESERVATION_HOLD, "RECONCILIATION_REQUIRED", PENDING),
                Arguments.of(RESERVATION_HOLD, "CONFIRMED", CONFIRMED),
                Arguments.of(RESERVATION_HOLD, "RELEASED", CANCELLED),
                Arguments.of(RESERVATION_HOLD, "EXPIRED", CANCELLED),
                Arguments.of(Source.RESERVATION, "CONFIRMED", CONFIRMED),
                Arguments.of(Source.RESERVATION, "FULFILLED", COMPLETED),
                Arguments.of(Source.RESERVATION, "CANCELLED", CANCELLED),
                Arguments.of(Source.RESERVATION, "NO_SHOW", NO_SHOW),
                Arguments.of(Source.WAITING, "WAITING", PENDING),
                Arguments.of(Source.WAITING, "CALLED", PENDING),
                Arguments.of(Source.WAITING, "RESERVATION_CONVERTING", PENDING),
                Arguments.of(Source.WAITING, "ARRIVED", CONFIRMED),
                Arguments.of(Source.WAITING, "CHECKED_IN", CHECKED_IN),
                Arguments.of(Source.WAITING, "RESERVATION_CONVERTED", CHECKED_IN),
                Arguments.of(Source.WAITING, "CLOSED_BY_STORE", COMPLETED),
                Arguments.of(Source.WAITING, "CANCELLED", CANCELLED),
                Arguments.of(Source.WAITING, "NO_SHOW", NO_SHOW));
    }
}
