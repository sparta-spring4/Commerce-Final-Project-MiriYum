package com.miriyum.domain.reservation.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.reservation.dto.contract.ReservationMonitoringContracts;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReservationMonitoringPublicContractTest {

    private static final Instant AS_OF = Instant.parse("2026-08-19T06:00:00Z");

    @Test
    void publicRecordsExposeNoEntityRepositoryOrPaymentProviderTypes() {
        for (Class<?> nested : ReservationMonitoringContracts.class.getDeclaredClasses()) {
            if (!nested.isRecord()) continue;
            assertThat(Arrays.stream(nested.getRecordComponents())
                    .map(RecordComponent::getGenericType).map(Object::toString))
                    .allMatch(type -> !type.contains(".entity.")
                            && !type.contains(".repository.")
                            && !type.toLowerCase().contains("portone")
                            && !type.toLowerCase().contains("provider"));
        }
    }

    @Test
    void queryAndBatchAreBoundedAndDefensivelyCopied() {
        ReservationMonitoringContracts.ChangeQuery query =
                new ReservationMonitoringContracts.ChangeQuery(
                        AS_OF, AS_OF.minusSeconds(3600), AS_OF, "12",
                        Set.of("CONFIRMED"), null, 100);
        assertThat(query.sourceStatuses()).containsExactly("CONFIRMED");
        assertThatThrownBy(() -> new ReservationMonitoringContracts.ChangeQuery(
                AS_OF, AS_OF, AS_OF.minusSeconds(1), null, Set.of(), null, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationMonitoringContracts.BatchQuery(
                AS_OF, java.util.stream.IntStream.rangeClosed(1, 101)
                .mapToObj(id -> "reservation:" + id).toList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotPreservesRawVersionsForHoldAndFinalReservation() {
        ReservationMonitoringContracts.LedgerCell hold = cell(
                "RESERVATION_HOLD", "CONFIRMED", 2, AS_OF.minusSeconds(20));
        ReservationMonitoringContracts.LedgerCell reservation = cell(
                "RESERVATION", "NO_SHOW", 1, AS_OF.minusSeconds(10));
        ReservationMonitoringContracts.CaseSnapshot snapshot =
                new ReservationMonitoringContracts.CaseSnapshot(
                        "reservation-hold:91", "12", 3,
                        AS_OF.plusSeconds(3600), AS_OF.plusSeconds(7200),
                        List.of(hold, reservation));

        assertThat(snapshot.caseId()).isEqualTo("reservation-hold:91");
        assertThat(snapshot.ledgers()).extracting(
                        ReservationMonitoringContracts.LedgerCell::source,
                        ReservationMonitoringContracts.LedgerCell::statusVersion)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("RESERVATION_HOLD", 2L),
                        org.assertj.core.groups.Tuple.tuple("RESERVATION", 1L));
    }

    @Test
    void historyMustBeVersionOrderedAndContainsNoContactReference() {
        ReservationMonitoringContracts.CaseSnapshot snapshot =
                new ReservationMonitoringContracts.CaseSnapshot(
                        "reservation:101", "12", 2,
                        AS_OF.plusSeconds(3600), AS_OF.plusSeconds(7200),
                        List.of(cell("RESERVATION", "CONFIRMED", 0, AS_OF.minusSeconds(20))));
        ReservationMonitoringContracts.Event v0 = new ReservationMonitoringContracts.Event(
                "RESERVATION", "CREATED", 0, null, "CONFIRMED", AS_OF.minusSeconds(20));
        ReservationMonitoringContracts.Event v1 = new ReservationMonitoringContracts.Event(
                "RESERVATION", "NO_SHOW", 1, "CONFIRMED", "NO_SHOW", AS_OF.minusSeconds(10));

        ReservationMonitoringContracts.Detail detail =
                new ReservationMonitoringContracts.Detail(snapshot, List.of(v0, v1));

        assertThat(detail.events()).containsExactly(v0, v1);
        assertThat(Arrays.stream(ReservationMonitoringContracts.Detail.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("consumerId", "notificationTargetReference", "rawContact");
        assertThatThrownBy(() -> new ReservationMonitoringContracts.Detail(
                snapshot, List.of(v1, v0))).isInstanceOf(IllegalArgumentException.class);
    }

    private static ReservationMonitoringContracts.LedgerCell cell(
            String source, String status, long version, Instant changedAt) {
        return new ReservationMonitoringContracts.LedgerCell(
                source, status, version, changedAt, AS_OF, AS_OF,
                ReservationMonitoringContracts.Completeness.COMPLETE,
                ReservationMonitoringContracts.ReconciliationStatus.MATCHED);
    }
}
