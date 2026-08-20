package com.miriyum.domain.reservation.waiting.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.reservation.waiting.dto.WaitingMonitoringContracts;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WaitingMonitoringPublicContractTest {

    private static final Instant AS_OF = Instant.parse("2026-08-19T06:00:00Z");

    @Test
    void publicRecordsExposeNoEntityRepositoryOrSensitiveSubjectTypes() {
        for (Class<?> nested : WaitingMonitoringContracts.class.getDeclaredClasses()) {
            if (!nested.isRecord()) continue;
            assertThat(Arrays.stream(nested.getRecordComponents())
                    .map(RecordComponent::getGenericType).map(Object::toString))
                    .allMatch(type -> !type.contains(".entity.")
                            && !type.contains(".repository.")
                            && !type.toLowerCase().contains("consumer")
                            && !type.toLowerCase().contains("actor"));
        }
    }

    @Test
    void changeAndBatchQueriesAreBounded() {
        var query = new WaitingMonitoringContracts.ChangeQuery(
                AS_OF, AS_OF.minusSeconds(60), AS_OF, "12",
                Set.of("CALLED"), null, 100);

        assertThat(query.sourceStatuses()).containsExactly("CALLED");
        assertThatThrownBy(() -> new WaitingMonitoringContracts.ChangeQuery(
                AS_OF, AS_OF, AS_OF.plusSeconds(1), null, Set.of(), null, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WaitingMonitoringContracts.BatchQuery(
                AS_OF, List.of("reservation:1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeQueryAllowsExactlyThirtyOneDaysAndRejectsAnyLongerRange() {
        assertThat(new WaitingMonitoringContracts.ChangeQuery(
                AS_OF, AS_OF.minus(Duration.ofDays(31)), AS_OF,
                null, Set.of(), null, 20)).isNotNull();

        assertThatThrownBy(() -> new WaitingMonitoringContracts.ChangeQuery(
                AS_OF, AS_OF.minus(Duration.ofDays(31)).minusNanos(1), AS_OF,
                null, Set.of(), null, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceCellPreservesRawVersionDataThroughAndConversionLinks() {
        var links = new WaitingMonitoringContracts.Links("501", "101");
        var cell = new WaitingMonitoringContracts.SourceCell(
                "waiting:7",
                new WaitingMonitoringContracts.ConfirmedState(
                        "12", "RESERVATION_CONVERTED", 4,
                        AS_OF.minusSeconds(5), 3, 18, links),
                AS_OF, AS_OF,
                WaitingMonitoringContracts.Completeness.COMPLETE,
                WaitingMonitoringContracts.ReconciliationStatus.MATCHED,
                AS_OF.minusSeconds(60));

        assertThat(cell.state().statusVersion()).isEqualTo(4);
        assertThat(cell.state().links()).isEqualTo(links);
        assertThat(Arrays.stream(cell.state().getClass().getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("consumerAccountId", "actorId", "reason");
        assertThatThrownBy(() -> new WaitingMonitoringContracts.SourceCell(
                "waiting:7",
                new WaitingMonitoringContracts.ConfirmedState(
                        "12", "CALLED", 1, AS_OF, 3, 18, links),
                AS_OF, AS_OF.plusSeconds(1),
                WaitingMonitoringContracts.Completeness.COMPLETE,
                WaitingMonitoringContracts.ReconciliationStatus.MATCHED,
                AS_OF.minusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unavailableCellCannotFabricateRawWaitingState() {
        var cell = new WaitingMonitoringContracts.SourceCell(
                "waiting:7", null, AS_OF, AS_OF,
                WaitingMonitoringContracts.Completeness.UNAVAILABLE,
                WaitingMonitoringContracts.ReconciliationStatus.UNKNOWN,
                AS_OF.plusSeconds(1));

        assertThat(cell.state()).isNull();
    }

    @Test
    void detailRequiresMonotonicVersionHistory() {
        var cell = cell();
        var v0 = new WaitingMonitoringContracts.Transition(
                0, null, "WAITING", AS_OF.minusSeconds(60));
        var v1 = new WaitingMonitoringContracts.Transition(
                1, "WAITING", "CALLED", AS_OF.minusSeconds(30));

        assertThat(new WaitingMonitoringContracts.Detail(cell, List.of(v0, v1)).history())
                .containsExactly(v0, v1);
        assertThatThrownBy(() -> new WaitingMonitoringContracts.Detail(
                cell, List.of(v1, v0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static WaitingMonitoringContracts.SourceCell cell() {
        return new WaitingMonitoringContracts.SourceCell(
                "waiting:7",
                new WaitingMonitoringContracts.ConfirmedState(
                        "12", "CALLED", 1, AS_OF.minusSeconds(30),
                        3, 18, new WaitingMonitoringContracts.Links(null, null)),
                AS_OF, AS_OF,
                WaitingMonitoringContracts.Completeness.COMPLETE,
                WaitingMonitoringContracts.ReconciliationStatus.MATCHED,
                AS_OF.minusSeconds(60));
    }
}
