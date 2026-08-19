package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.reservation.dto.contract.ReservationMonitoringContracts;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCheckInAudit;
import com.miriyum.domain.reservation.entity.ReservationCheckInEventType;
import com.miriyum.domain.reservation.entity.ReservationHold;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.entity.ReservationHoldTransitionAudit;
import com.miriyum.domain.reservation.entity.ReservationNoShowAudit;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.repository.ReservationCheckInAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.repository.ReservationFulfillmentAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldTransitionAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationNoShowAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ReservationMonitoringQueryServiceTest {

    private static final Instant CREATED = Instant.parse("2026-08-19T05:00:00Z");
    private static final Instant TERMINAL = Instant.parse("2026-08-19T05:30:00Z");
    private static final Instant AS_OF = Instant.parse("2026-08-19T06:00:00Z");

    @Mock ReservationRepository reservationRepository;
    @Mock ReservationHoldRepository holdRepository;
    @Mock ReservationHoldTransitionAuditRepository holdAuditRepository;
    @Mock ReservationDepositProcessRepository processRepository;
    @Mock ReservationCheckInAuditRepository checkInAuditRepository;
    @Mock ReservationFulfillmentAuditRepository fulfillmentAuditRepository;
    @Mock ReservationNoShowAuditRepository noShowAuditRepository;

    private ReservationMonitoringQueryService service;

    @BeforeEach
    void setUp() {
        service = new ReservationMonitoringQueryService(
                reservationRepository, holdRepository, holdAuditRepository, processRepository,
                checkInAuditRepository, fulfillmentAuditRepository, noShowAuditRepository);
    }

    @Test
    void directReservationIsReconstructedAtAsOfBeforeItsLaterTerminalState() {
        Reservation reservation = reservation(101L, ReservationStatus.NO_SHOW, TERMINAL);
        given(reservationRepository.findAllByIdIn(List.of(101L))).willReturn(List.of(reservation));
        given(processRepository.findMonitoringLinks(List.of(), List.of(101L)))
                .willReturn(List.of());
        given(holdRepository.findAllByIdIn(List.of())).willReturn(List.of());
        Instant beforeTerminal = TERMINAL.minusSeconds(1);

        ReservationMonitoringContracts.CaseSnapshot result = service.findCases(
                        new ReservationMonitoringContracts.BatchQuery(
                                beforeTerminal, List.of("reservation:101")))
                .cases().getFirst();

        assertThat(result.caseId()).isEqualTo("reservation:101");
        assertThat(result.ledgers()).singleElement().satisfies(cell -> {
            assertThat(cell.source()).isEqualTo("RESERVATION");
            assertThat(cell.sourceStatus()).isEqualTo("CONFIRMED");
            assertThat(cell.statusVersion()).isZero();
            assertThat(cell.statusChangedAt()).isEqualTo(CREATED);
        });
    }

    @Test
    void finalReservationKeepsReservationHoldCaseAndBothRawVersions() {
        ReservationHold hold = hold(91L);
        Reservation reservation = reservation(101L, ReservationStatus.NO_SHOW, TERMINAL);
        ReservationDepositProcessRepository.MonitoringLink link =
                mock(ReservationDepositProcessRepository.MonitoringLink.class);
        given(link.getReservationHoldId()).willReturn(91L);
        given(link.getFinalReservationId()).willReturn(101L);
        given(processRepository.findMonitoringLinks(List.of(91L), List.of()))
                .willReturn(List.of(link));
        given(holdRepository.findAllByIdIn(List.of(91L))).willReturn(List.of(hold));
        given(reservationRepository.findAllByIdIn(List.of(101L))).willReturn(List.of(reservation));
        List<ReservationHoldTransitionAudit> history = List.of(
                holdAudit(91L, null, ReservationHoldStatus.ACTIVE, CREATED),
                holdAudit(91L, ReservationHoldStatus.ACTIVE,
                        ReservationHoldStatus.RECONCILIATION_REQUIRED,
                        CREATED.plusSeconds(10)),
                holdAudit(91L, ReservationHoldStatus.RECONCILIATION_REQUIRED,
                        ReservationHoldStatus.CONFIRMED, CREATED.plusSeconds(20)));
        given(holdAuditRepository.findAllByReservationHoldIdInOrderByReservationHoldIdAscIdAsc(
                List.of(91L))).willReturn(history);

        ReservationMonitoringContracts.CaseSnapshot result = service.findCases(
                        new ReservationMonitoringContracts.BatchQuery(
                                AS_OF, List.of("reservation-hold:91")))
                .cases().getFirst();

        assertThat(result.caseId()).isEqualTo("reservation-hold:91");
        assertThat(result.ledgers()).extracting(
                        ReservationMonitoringContracts.LedgerCell::source,
                        ReservationMonitoringContracts.LedgerCell::sourceStatus,
                        ReservationMonitoringContracts.LedgerCell::statusVersion)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "RESERVATION_HOLD", "CONFIRMED", 2L),
                        org.assertj.core.groups.Tuple.tuple(
                                "RESERVATION", "NO_SHOW", 1L));
    }

    @Test
    void changedCasesUseStableCaseIdAndFixedOrdering() {
        Reservation reservation = reservation(101L, ReservationStatus.NO_SHOW, TERMINAL);
        ReservationDepositProcessRepository.MonitoringLink link =
                mock(ReservationDepositProcessRepository.MonitoringLink.class);
        given(link.getReservationHoldId()).willReturn(91L);
        given(link.getFinalReservationId()).willReturn(101L);
        given(reservationRepository.findMonitoringChanges(
                AS_OF.minusSeconds(3600), AS_OF, null, Pageable.ofSize(100)))
                .willReturn(List.of(reservation));
        given(holdAuditRepository.findByOccurredAtBetweenOrderByOccurredAtDescIdDesc(
                AS_OF.minusSeconds(3600), AS_OF, Pageable.ofSize(100)))
                .willReturn(List.of());
        given(checkInAuditRepository.findByOccurredAtBetweenOrderByOccurredAtDescIdDesc(
                AS_OF.minusSeconds(3600), AS_OF, Pageable.ofSize(100)))
                .willReturn(List.of());
        given(processRepository.findMonitoringLinks(List.of(), List.of(101L)))
                .willReturn(List.of(link));

        ReservationMonitoringContracts.ReferencePage page = service.findChangedCases(
                new ReservationMonitoringContracts.ChangeQuery(
                        AS_OF, AS_OF.minusSeconds(3600), AS_OF, null,
                        Set.of("NO_SHOW"), null, 20));

        assertThat(page.items()).containsExactly(new ReservationMonitoringContracts.CaseReference(
                "reservation-hold:91", "12", TERMINAL));
    }

    @Test
    void detailIncludesCheckInAndNoShowEventsWithoutContactReference() {
        Reservation reservation = reservation(101L, ReservationStatus.NO_SHOW, TERMINAL);
        ReservationCheckInAudit checkIn = mock(ReservationCheckInAudit.class);
        given(checkIn.getEventType()).willReturn(ReservationCheckInEventType.QR_GRANT_ISSUED);
        given(checkIn.getBeforeStatus()).willReturn(ReservationStatus.CONFIRMED);
        given(checkIn.getAfterStatus()).willReturn(ReservationStatus.CONFIRMED);
        given(checkIn.getOccurredAt()).willReturn(CREATED.plusSeconds(100));
        ReservationNoShowAudit noShow = mock(ReservationNoShowAudit.class);
        given(noShow.getBeforeStatus()).willReturn(ReservationStatus.CONFIRMED);
        given(noShow.getAfterStatus()).willReturn(ReservationStatus.NO_SHOW);
        given(noShow.getOccurredAt()).willReturn(TERMINAL);
        given(reservationRepository.findById(101L)).willReturn(Optional.of(reservation));
        given(processRepository.findMonitoringLinks(List.of(), List.of(101L)))
                .willReturn(List.of());
        given(checkInAuditRepository.findAllByReservationIdInOrderByOccurredAtAscIdAsc(List.of(101L)))
                .willReturn(List.of(checkIn));
        given(noShowAuditRepository.findAllByReservationIdInOrderByOccurredAtAscIdAsc(List.of(101L)))
                .willReturn(List.of(noShow));
        given(fulfillmentAuditRepository.findAllByReservationIdInOrderByOccurredAtAscIdAsc(List.of(101L)))
                .willReturn(List.of());

        ReservationMonitoringContracts.Detail detail = service.findCase(
                new ReservationMonitoringContracts.DetailQuery(
                        AS_OF, "reservation:101")).orElseThrow();

        assertThat(detail.events()).extracting(
                        ReservationMonitoringContracts.Event::source,
                        ReservationMonitoringContracts.Event::eventType,
                        ReservationMonitoringContracts.Event::afterStatus)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(
                                "RESERVATION", "QR_GRANT_ISSUED", "CONFIRMED"),
                        org.assertj.core.groups.Tuple.tuple(
                                "RESERVATION", "NO_SHOW", "NO_SHOW"));
    }

    private static Reservation reservation(
            long id, ReservationStatus status, Instant terminalAt) {
        Reservation reservation = mock(Reservation.class);
        lenient().when(reservation.getId()).thenReturn(id);
        lenient().when(reservation.getStoreId()).thenReturn(12L);
        lenient().when(reservation.getCreatedAt()).thenReturn(CREATED);
        lenient().when(reservation.getStartAt()).thenReturn(AS_OF.plusSeconds(3600));
        lenient().when(reservation.getServiceEndAt()).thenReturn(AS_OF.plusSeconds(7200));
        PartyComposition party = mock(PartyComposition.class);
        lenient().when(party.totalCount()).thenReturn(3);
        lenient().when(reservation.getParty()).thenReturn(party);
        if (status == ReservationStatus.NO_SHOW) {
            lenient().when(reservation.getNoShowAt()).thenReturn(terminalAt);
        }
        if (status == ReservationStatus.FULFILLED) {
            lenient().when(reservation.getFulfilledAt()).thenReturn(terminalAt);
        }
        if (status == ReservationStatus.CANCELLED) {
            lenient().when(reservation.getCancelledAt()).thenReturn(terminalAt);
        }
        return reservation;
    }

    private static ReservationHold hold(long id) {
        ReservationHold hold = mock(ReservationHold.class);
        given(hold.getId()).willReturn(id);
        given(hold.getStoreId()).willReturn(12L);
        given(hold.getCreatedAt()).willReturn(CREATED);
        given(hold.getStartAt()).willReturn(AS_OF.plusSeconds(3600));
        given(hold.getServiceEndAt()).willReturn(AS_OF.plusSeconds(7200));
        PartyComposition party = mock(PartyComposition.class);
        given(party.totalCount()).willReturn(3);
        given(hold.getParty()).willReturn(party);
        return hold;
    }

    private static ReservationHoldTransitionAudit holdAudit(
            long id, ReservationHoldStatus before, ReservationHoldStatus after, Instant occurredAt) {
        ReservationHoldTransitionAudit audit = mock(ReservationHoldTransitionAudit.class);
        given(audit.getReservationHoldId()).willReturn(id);
        lenient().when(audit.getBeforeStatus()).thenReturn(before);
        lenient().when(audit.getAfterStatus()).thenReturn(after);
        given(audit.getOccurredAt()).willReturn(occurredAt);
        return audit;
    }
}
