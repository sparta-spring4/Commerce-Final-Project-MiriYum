package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.reservation.waiting.dto.WaitingMonitoringContracts;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTransitionAuditRepository;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class WaitingMonitoringQueryServiceTest {

    private static final Instant CREATED = Instant.parse("2026-08-19T05:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-08-19T06:00:00Z");

    @Mock WaitingTeamRepository teamRepository;
    @Mock WaitingTransitionAuditRepository auditRepository;

    private WaitingMonitoringQueryService service;

    @BeforeEach
    void setUp() {
        service = new WaitingMonitoringQueryService(teamRepository, auditRepository);
    }

    @Test
    void changedCasesUseAuditTimeStatusFilterAndDeterministicSeek() {
        var later = audit(8, 12, WaitingTeamStatus.WAITING,
                WaitingTeamStatus.CALLED, 1, AS_OF.minusSeconds(5));
        var earlier = audit(7, 12, null,
                WaitingTeamStatus.WAITING, 0, AS_OF.minusSeconds(20));
        given(auditRepository.findMonitoringChanges(
                AS_OF.minusSeconds(60), AS_OF, 12L, true,
                Set.of(WaitingTeamStatus.values()), null, null, Pageable.ofSize(20)))
                .willReturn(List.of(later, earlier));

        var page = service.findChangedCases(new WaitingMonitoringContracts.ChangeQuery(
                AS_OF, AS_OF.minusSeconds(60), AS_OF, "12", Set.of(), null, 20));

        assertThat(page.items()).extracting(WaitingMonitoringContracts.CaseReference::caseId)
                .containsExactly("waiting:8", "waiting:7");
    }

    @Test
    void sourceFailureIsTypedAndCannotMasqueradeAsEmptyPage() {
        given(auditRepository.findMonitoringChanges(
                AS_OF.minusSeconds(60), AS_OF, null, true,
                Set.of(WaitingTeamStatus.values()), null, null, Pageable.ofSize(20)))
                .willThrow(new DataAccessResourceFailureException("waiting unavailable"));

        assertThatThrownBy(() -> service.findChangedCases(
                new WaitingMonitoringContracts.ChangeQuery(
                        AS_OF, AS_OF.minusSeconds(60), AS_OF, null, Set.of(), null, 20)))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(
                                ReservationErrorCode.WAITING_MONITORING_UNAVAILABLE));
    }

    @Test
    void batchReconstructsRawVersionAtAsOfAndDoesNotUseCurrentFutureState() {
        WaitingTeam team = team(7, WaitingTeamStatus.NO_SHOW, 3, null, null);
        var created = audit(7, 12, null,
                WaitingTeamStatus.WAITING, 0, CREATED);
        var called = audit(7, 12, WaitingTeamStatus.WAITING,
                WaitingTeamStatus.CALLED, 1, AS_OF.minusSeconds(10));
        var futureNoShow = audit(7, 12, WaitingTeamStatus.CALLED,
                WaitingTeamStatus.NO_SHOW, 2, AS_OF.plusSeconds(10));
        given(teamRepository.findAllByIdIn(List.of(7L))).willReturn(List.of(team));
        given(auditRepository.findMonitoringHistory(List.of(7L)))
                .willReturn(List.of(created, called, futureNoShow));

        var cell = service.findCases(new WaitingMonitoringContracts.BatchQuery(
                AS_OF, List.of("waiting:7"))).cells().getFirst();

        assertThat(cell.state().sourceStatus()).isEqualTo("CALLED");
        assertThat(cell.state().statusVersion()).isEqualTo(1);
        assertThat(cell.state().statusChangedAt()).isEqualTo(AS_OF.minusSeconds(10));
    }

    @Test
    void beforeFirstAuditIsUnavailableInsteadOfCurrentSnapshotFallback() {
        WaitingTeam team = team(7, WaitingTeamStatus.CALLED, 1, null, null);
        var baseline = audit(7, 12, null,
                WaitingTeamStatus.WAITING, 0, CREATED.plusSeconds(30));
        given(teamRepository.findAllByIdIn(List.of(7L))).willReturn(List.of(team));
        given(auditRepository.findMonitoringHistory(List.of(7L))).willReturn(List.of(baseline));

        var cell = service.findCases(new WaitingMonitoringContracts.BatchQuery(
                CREATED.plusSeconds(10), List.of("waiting:7"))).cells().getFirst();

        assertThat(cell.state()).isNull();
        assertThat(cell.completeness())
                .isEqualTo(WaitingMonitoringContracts.Completeness.UNAVAILABLE);
        assertThat(cell.historyAvailableFrom()).isEqualTo(CREATED.plusSeconds(30));
    }

    @Test
    void detailPublishesConversionCorrelationButNoSubjectOrActor() {
        WaitingTeam team = team(7, WaitingTeamStatus.RESERVATION_CONVERTED, 2, "501", 101L);
        var created = audit(7, 12, null,
                WaitingTeamStatus.WAITING, 0, CREATED);
        var converted = audit(7, 12, WaitingTeamStatus.RESERVATION_CONVERTING,
                WaitingTeamStatus.RESERVATION_CONVERTED, 2, AS_OF.minusSeconds(5));
        given(teamRepository.findAllByIdIn(List.of(7L))).willReturn(List.of(team));
        given(auditRepository.findMonitoringHistory(List.of(7L)))
                .willReturn(List.of(created, converted));

        var detail = service.findCase(new WaitingMonitoringContracts.DetailQuery(
                AS_OF, "waiting:7")).orElseThrow();

        assertThat(detail.cell().state().links())
                .isEqualTo(new WaitingMonitoringContracts.Links("501", "101"));
        assertThat(detail.history()).extracting(
                        WaitingMonitoringContracts.Transition::afterStatus,
                        WaitingMonitoringContracts.Transition::resultVersion)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("WAITING", 0L),
                        org.assertj.core.groups.Tuple.tuple("RESERVATION_CONVERTED", 2L));
    }

    private static WaitingTeam team(
            long id,
            WaitingTeamStatus status,
            long version,
            String paymentId,
            Long reservationId
    ) {
        WaitingTeam team = mock(WaitingTeam.class);
        lenient().when(team.getId()).thenReturn(id);
        lenient().when(team.getStoreId()).thenReturn(12L);
        lenient().when(team.getStatus()).thenReturn(status);
        lenient().when(team.getVersion()).thenReturn(version);
        lenient().when(team.getCreatedAt()).thenReturn(CREATED);
        lenient().when(team.getPartySize()).thenReturn(3);
        lenient().when(team.getQueueSequence()).thenReturn(18L);
        lenient().when(team.getWaitingPaymentId()).thenReturn(paymentId);
        lenient().when(team.getReservationReferenceId()).thenReturn(reservationId);
        return team;
    }

    private static WaitingTransitionAuditRepository.MonitoringTransition audit(
            long teamId,
            long storeId,
            WaitingTeamStatus before,
            WaitingTeamStatus after,
            long resultVersion,
            Instant occurredAt
    ) {
        var audit = mock(WaitingTransitionAuditRepository.MonitoringTransition.class);
        lenient().when(audit.getWaitingTeamId()).thenReturn(teamId);
        lenient().when(audit.getStoreId()).thenReturn(storeId);
        lenient().when(audit.getBeforeStatus()).thenReturn(before);
        lenient().when(audit.getAfterStatus()).thenReturn(after);
        lenient().when(audit.getResultVersion()).thenReturn(resultVersion);
        lenient().when(audit.getOccurredAt()).thenReturn(occurredAt);
        return audit;
    }
}
