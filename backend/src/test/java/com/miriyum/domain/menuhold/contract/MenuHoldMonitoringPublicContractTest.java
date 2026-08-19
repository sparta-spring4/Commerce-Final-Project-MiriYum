package com.miriyum.domain.menuhold.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.menuhold.dto.MenuHoldMonitoringContracts;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MenuHoldMonitoringPublicContractTest {

    private static final Instant AS_OF = Instant.parse("2026-08-19T06:00:00Z");

    @Test
    void publicRecordsExposeNoEntityRepositoryOrProviderTypes() {
        for (Class<?> nested : MenuHoldMonitoringContracts.class.getDeclaredClasses()) {
            if (!nested.isRecord()) {
                continue;
            }
            assertThat(Arrays.stream(nested.getRecordComponents())
                    .map(RecordComponent::getGenericType)
                    .map(Object::toString))
                    .allMatch(type -> !type.contains(".entity.")
                            && !type.contains(".repository.")
                            && !type.toLowerCase().contains("provider"));
        }
    }

    @Test
    void changeQueryRequiresBoundedTimeAndPageSize() {
        MenuHoldMonitoringContracts.ChangeQuery query =
                new MenuHoldMonitoringContracts.ChangeQuery(
                        AS_OF, AS_OF.minusSeconds(3600), AS_OF, "12",
                        Set.of("ACTIVE"), null, 100);

        assertThat(query.sourceStatuses()).containsExactly("ACTIVE");
        assertThatThrownBy(() -> new MenuHoldMonitoringContracts.ChangeQuery(
                AS_OF, AS_OF, AS_OF.minusSeconds(1), null, Set.of(), null, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MenuHoldMonitoringContracts.ChangeQuery(
                AS_OF, AS_OF.minusSeconds(1), AS_OF, null, Set.of(), null, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceCellPreservesBaselineBoundaryAndRawVersion() {
        Instant baseline = AS_OF.minusSeconds(120);
        MenuHoldMonitoringContracts.SourceCell cell =
                new MenuHoldMonitoringContracts.SourceCell(
                        "reservation-hold:91", "12",
                        new MenuHoldMonitoringContracts.ConfirmedState("ACTIVE", 0, baseline),
                        AS_OF, baseline,
                        MenuHoldMonitoringContracts.Completeness.DELAYED,
                        MenuHoldMonitoringContracts.ReconciliationStatus.MATCHED,
                        baseline,
                        new MenuHoldMonitoringContracts.Links("101", "91"));

        assertThat(cell.state().statusVersion()).isZero();
        assertThat(cell.historyAvailableFrom()).isEqualTo(baseline);
        assertThatThrownBy(() -> new MenuHoldMonitoringContracts.SourceCell(
                "reservation-hold:91", "12",
                new MenuHoldMonitoringContracts.ConfirmedState("ACTIVE", 0, baseline),
                AS_OF, AS_OF.plusSeconds(1),
                MenuHoldMonitoringContracts.Completeness.COMPLETE,
                MenuHoldMonitoringContracts.ReconciliationStatus.MATCHED,
                baseline, cell.links()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unavailableCellCannotFabricateRawStateOrExposeFutureCorrelation() {
        MenuHoldMonitoringContracts.SourceCell unavailable =
                new MenuHoldMonitoringContracts.SourceCell(
                        "reservation-hold:91", null, null,
                        AS_OF, AS_OF,
                        MenuHoldMonitoringContracts.Completeness.UNAVAILABLE,
                        MenuHoldMonitoringContracts.ReconciliationStatus.UNKNOWN,
                        AS_OF.plusSeconds(1), null);

        assertThat(unavailable.state()).isNull();
        assertThat(unavailable.storeId()).isNull();
        assertThat(unavailable.links()).isNull();
        assertThatThrownBy(() -> new MenuHoldMonitoringContracts.SourceCell(
                "reservation-hold:91", "12",
                new MenuHoldMonitoringContracts.ConfirmedState("ACTIVE", 0, AS_OF),
                AS_OF, AS_OF,
                MenuHoldMonitoringContracts.Completeness.UNAVAILABLE,
                MenuHoldMonitoringContracts.ReconciliationStatus.UNKNOWN,
                AS_OF, new MenuHoldMonitoringContracts.Links(null, "91")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detailCopiesHistoryAndItemsAndRejectsUnsortedVersions() {
        MenuHoldMonitoringContracts.SourceCell cell = cell();
        MenuHoldMonitoringContracts.Transition v0 =
                new MenuHoldMonitoringContracts.Transition(
                        0, null, "ACTIVE", AS_OF.minusSeconds(10));
        MenuHoldMonitoringContracts.Transition v1 =
                new MenuHoldMonitoringContracts.Transition(
                        1, "ACTIVE", "CONFIRMED", AS_OF.minusSeconds(5));
        MenuHoldMonitoringContracts.Item item =
                new MenuHoldMonitoringContracts.Item("77", "아메리카노", 2);

        MenuHoldMonitoringContracts.Detail detail =
                new MenuHoldMonitoringContracts.Detail(cell, List.of(v0, v1), List.of(item));

        assertThat(detail.history()).containsExactly(v0, v1);
        assertThat(detail.items()).containsExactly(item);
        assertThatThrownBy(() -> new MenuHoldMonitoringContracts.Detail(
                cell, List.of(v1, v0), List.of(item)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MenuHoldMonitoringContracts.SourceCell cell() {
        return new MenuHoldMonitoringContracts.SourceCell(
                "reservation-hold:91", "12",
                new MenuHoldMonitoringContracts.ConfirmedState(
                        "CONFIRMED", 1, AS_OF.minusSeconds(5)),
                AS_OF, AS_OF,
                MenuHoldMonitoringContracts.Completeness.COMPLETE,
                MenuHoldMonitoringContracts.ReconciliationStatus.MATCHED,
                AS_OF.minusSeconds(10),
                new MenuHoldMonitoringContracts.Links("101", "91"));
    }
}
