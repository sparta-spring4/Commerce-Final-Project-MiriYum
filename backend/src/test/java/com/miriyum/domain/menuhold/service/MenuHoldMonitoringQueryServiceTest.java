package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.menuhold.dto.MenuHoldMonitoringContracts;
import com.miriyum.domain.menuhold.entity.MenuHold;
import com.miriyum.domain.menuhold.entity.MenuHoldItemSnapshot;
import com.miriyum.domain.menuhold.entity.MenuHoldStatus;
import com.miriyum.domain.menuhold.entity.MenuHoldTransitionAudit;
import com.miriyum.domain.menuhold.repository.MenuHoldTransitionAuditRepository;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MenuHoldMonitoringQueryServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-19T05:00:00Z");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-08-19T05:10:00Z");
    private static final Instant AS_OF = Instant.parse("2026-08-19T06:00:00Z");

    @Mock MenuHoldTransitionAuditRepository auditRepository;

    private MenuHold hold;
    private MenuHoldTransitionAudit created;
    private MenuHoldTransitionAudit confirmed;
    private MenuHoldMonitoringQueryService service;

    @BeforeEach
    void setUp() {
        hold = MenuHold.temporaryActive(
                91L, 12L, 13L,
                LocalDate.of(2026, 8, 20), LocalTime.NOON,
                LocalDate.of(2026, 8, 20), LocalTime.of(13, 0),
                Instant.parse("2026-08-19T05:30:00Z"), "operation-91",
                List.of(new MenuHoldItemSnapshot(
                        77L, 88L, 1L, "아메리카노", 5_000, 1L, 2)));
        ReflectionTestUtils.setField(hold, "id", 55L);
        created = MenuHoldTransitionAudit.created(hold, CREATED_AT);
        MenuHoldStatus before = hold.getStatus();
        hold.confirmTemporary(101L);
        confirmed = MenuHoldTransitionAudit.transition(hold, before, CONFIRMED_AT);
        service = new MenuHoldMonitoringQueryService(auditRepository);
    }

    @Test
    void changedCasesDeduplicateByStableReservationHoldCaseAndApplySeek() {
        given(auditRepository.findMonitoringChanges(
                AS_OF.minusSeconds(7200), AS_OF, 12L, false,
                Set.of(MenuHoldStatus.CONFIRMED), null, null, Pageable.ofSize(2)))
                .willReturn(List.of(confirmed, created));

        MenuHoldMonitoringContracts.ReferencePage page = service.findChangedCases(
                new MenuHoldMonitoringContracts.ChangeQuery(
                        AS_OF, AS_OF.minusSeconds(7200), AS_OF, "12",
                        Set.of("CONFIRMED"), null, 2));

        assertThat(page.items()).containsExactly(new MenuHoldMonitoringContracts.CaseReference(
                "reservation-hold:91", "12", CONFIRMED_AT));
        assertThat(page.asOf()).isEqualTo(AS_OF);
        assertThat(page.dataThrough()).isEqualTo(AS_OF);
    }

    @Test
    void batchReconstructsLatestAuditAtAsOfInsteadOfCurrentEntityState() {
        Instant beforeConfirmation = CONFIRMED_AT.minusSeconds(1);
        given(auditRepository.findByReservationHoldIdInOrderByMenuHoldIdAscResultVersionAsc(
                List.of(91L)))
                .willReturn(List.of(created, confirmed));

        MenuHoldMonitoringContracts.BatchResult result = service.findCases(
                new MenuHoldMonitoringContracts.BatchQuery(
                        beforeConfirmation, List.of("reservation-hold:91")));

        assertThat(result.cells()).singleElement().satisfies(cell -> {
            assertThat(cell.state().sourceStatus()).isEqualTo("ACTIVE");
            assertThat(cell.state().statusVersion()).isZero();
            assertThat(cell.state().statusChangedAt()).isEqualTo(CREATED_AT);
            assertThat(cell.links().reservationId()).isNull();
            assertThat(cell.links().reservationHoldId()).isEqualTo("91");
        });
    }

    @Test
    void beforeBaselineReturnsUnavailableWithoutInventingHistory() {
        Instant beforeBaseline = CREATED_AT.minusSeconds(1);
        ReflectionTestUtils.setField(
                created, "eventType", MenuHoldTransitionAudit.EventType.BASELINE);
        ReflectionTestUtils.setField(
                created, "holdCreatedAt", CREATED_AT.minusSeconds(60));
        given(auditRepository.findByReservationHoldIdInOrderByMenuHoldIdAscResultVersionAsc(
                List.of(91L)))
                .willReturn(List.of(created, confirmed));

        MenuHoldMonitoringContracts.SourceCell cell = service.findCases(
                        new MenuHoldMonitoringContracts.BatchQuery(
                                beforeBaseline, List.of("reservation-hold:91")))
                .cells().getFirst();

        assertThat(cell.completeness())
                .isEqualTo(MenuHoldMonitoringContracts.Completeness.UNAVAILABLE);
        assertThat(cell.state()).isNull();
        assertThat(cell.storeId()).isNull();
        assertThat(cell.links()).isNull();
        assertThat(cell.historyAvailableFrom()).isEqualTo(CREATED_AT);
    }

    @Test
    void detailReturnsOrderedTransitionsAndMinimumMenuSnapshot() {
        given(auditRepository.findByReservationHoldIdInOrderByMenuHoldIdAscResultVersionAsc(
                List.of(91L)))
                .willReturn(List.of(created, confirmed));

        MenuHoldMonitoringContracts.Detail detail = service.findCase(
                new MenuHoldMonitoringContracts.DetailQuery(
                        AS_OF, "reservation-hold:91")).orElseThrow();

        assertThat(detail.history()).extracting(
                        MenuHoldMonitoringContracts.Transition::resultVersion,
                        MenuHoldMonitoringContracts.Transition::afterStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0L, "ACTIVE"),
                        org.assertj.core.groups.Tuple.tuple(1L, "CONFIRMED"));
        assertThat(detail.items()).containsExactly(
                new MenuHoldMonitoringContracts.Item("77", "아메리카노", 2));
    }

    @Test
    void sourceFailureIsTypedAndCannotMasqueradeAsEmptyPage() {
        given(auditRepository.findMonitoringChanges(
                AS_OF.minusSeconds(60), AS_OF, null, true,
                Set.of(MenuHoldStatus.values()), null, null, Pageable.ofSize(20)))
                .willThrow(new DataAccessResourceFailureException("menu hold unavailable"));

        assertThatThrownBy(() -> service.findChangedCases(
                new MenuHoldMonitoringContracts.ChangeQuery(
                        AS_OF, AS_OF.minusSeconds(60), AS_OF, null, Set.of(), null, 20)))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(
                                MenuHoldErrorCode.MONITORING_SOURCE_UNAVAILABLE));
    }

    @Test
    void postAsOfHoldIsAbsentFromBatchAndDetail() {
        Instant beforeCreation = CREATED_AT.minusSeconds(1);
        ReflectionTestUtils.setField(
                hold, "createdAt", LocalDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC));
        given(auditRepository.findByReservationHoldIdInOrderByMenuHoldIdAscResultVersionAsc(
                List.of(91L)))
                .willReturn(List.of(created, confirmed));

        assertThat(service.findCases(new MenuHoldMonitoringContracts.BatchQuery(
                beforeCreation, List.of("reservation-hold:91"))).cells()).isEmpty();
        assertThat(service.findCase(new MenuHoldMonitoringContracts.DetailQuery(
                beforeCreation, "reservation-hold:91"))).isEmpty();
    }

    @Test
    void preBaselineDetailDoesNotExposeCurrentItemsOrLinks() {
        Instant beforeBaseline = CREATED_AT.minusSeconds(1);
        ReflectionTestUtils.setField(
                created, "eventType", MenuHoldTransitionAudit.EventType.BASELINE);
        ReflectionTestUtils.setField(
                created, "holdCreatedAt", CREATED_AT.minusSeconds(60));
        given(auditRepository.findByReservationHoldIdInOrderByMenuHoldIdAscResultVersionAsc(
                List.of(91L))).willReturn(List.of(created, confirmed));

        MenuHoldMonitoringContracts.Detail detail = service.findCase(
                new MenuHoldMonitoringContracts.DetailQuery(
                        beforeBaseline, "reservation-hold:91")).orElseThrow();

        assertThat(detail.cell().completeness())
                .isEqualTo(MenuHoldMonitoringContracts.Completeness.UNAVAILABLE);
        assertThat(detail.cell().links()).isNull();
        assertThat(detail.items()).isEmpty();
    }
}
