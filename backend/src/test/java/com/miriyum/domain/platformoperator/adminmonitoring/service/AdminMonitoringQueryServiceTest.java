package com.miriyum.domain.platformoperator.adminmonitoring.service;

import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseType.RESERVATION;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseType.WAITING;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Completeness.PARTIAL;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.LifecycleStatus.CHECKED_IN;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Source.MENU_HOLD;
import static com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.Source.RESERVATION_HOLD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.menuhold.dto.MenuHoldMonitoringContracts;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.service.MenuHoldMonitoringQueryService;
import com.miriyum.domain.payment.dto.PaymentMonitoringContracts;
import com.miriyum.domain.payment.service.PaymentMonitoringQueryService;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringRequests.ListQuery;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CasePage;
import com.miriyum.domain.platformoperator.adminmonitoring.exception.AdminMonitoringErrorCode;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.reservation.dto.contract.ReservationMonitoringContracts;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.service.ReservationMonitoringQueryService;
import com.miriyum.domain.reservation.waiting.dto.WaitingMonitoringContracts;
import com.miriyum.domain.reservation.waiting.service.WaitingMonitoringQueryService;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminMonitoringQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
    private static final Instant CHANGED = NOW.minusSeconds(30);
    private static final PlatformOperatorPrincipal PRINCIPAL = new PlatformOperatorPrincipal(
            17L, "operator@example.com", "session", 3L, 2L, false);

    private ReservationMonitoringQueryService reservations;
    private WaitingMonitoringQueryService waitings;
    private MenuHoldMonitoringQueryService menuHolds;
    private PaymentMonitoringQueryService payments;
    private AdminMonitoringCursorCodec cursors;
    private AdminMonitoringAuthorizationService authorization;
    private AdminMonitoringQueryService service;

    @BeforeEach
    void setUp() {
        reservations = mock(ReservationMonitoringQueryService.class);
        waitings = mock(WaitingMonitoringQueryService.class);
        menuHolds = mock(MenuHoldMonitoringQueryService.class);
        payments = mock(PaymentMonitoringQueryService.class);
        cursors = mock(AdminMonitoringCursorCodec.class);
        authorization = mock(AdminMonitoringAuthorizationService.class);
        service = new AdminMonitoringQueryService(
                reservations, waitings, menuHolds, payments,
                new AdminMonitoringStatusMapper(), cursors, authorization,
                Clock.fixed(NOW, ZoneOffset.UTC));
        emptySourceDefaults();
    }

    @Test
    void mergesCaseTypesInFixedTieOrderAndPassesOneAsOfToEverySource() {
        willReturn(new ReservationMonitoringContracts.ReferencePage(
                List.of(new ReservationMonitoringContracts.CaseReference(
                        "reservation-hold:12", "7", CHANGED)), NOW, NOW))
                .given(reservations).findChangedCases(any());
        willReturn(new WaitingMonitoringContracts.ReferencePage(
                List.of(new WaitingMonitoringContracts.CaseReference("waiting:9", "7", CHANGED)), NOW, NOW))
                .given(waitings).findChangedCases(any());
        willReturn(reservationBatch("reservation-hold:12")).given(reservations).findCases(any());
        willReturn(waitingBatch("waiting:9")).given(waitings).findCases(any());

        CasePage page = service.list(PRINCIPAL, query(2));

        assertThat(page.items()).extracting(item -> item.caseId())
                .containsExactly("reservation-hold:12", "waiting:9");
        assertThat(page.asOf()).isEqualTo(NOW);
        assertThat(page.nextCursor()).isNull();

        ArgumentCaptor<ReservationMonitoringContracts.ChangeQuery> reservationChange =
                ArgumentCaptor.forClass(ReservationMonitoringContracts.ChangeQuery.class);
        ArgumentCaptor<WaitingMonitoringContracts.ChangeQuery> waitingChange =
                ArgumentCaptor.forClass(WaitingMonitoringContracts.ChangeQuery.class);
        ArgumentCaptor<MenuHoldMonitoringContracts.BatchQuery> menuBatch =
                ArgumentCaptor.forClass(MenuHoldMonitoringContracts.BatchQuery.class);
        ArgumentCaptor<PaymentMonitoringContracts.BatchQuery> paymentBatch =
                ArgumentCaptor.forClass(PaymentMonitoringContracts.BatchQuery.class);
        then(reservations).should().findChangedCases(reservationChange.capture());
        then(waitings).should().findChangedCases(waitingChange.capture());
        then(menuHolds).should().findCases(menuBatch.capture());
        then(payments).should().findCases(paymentBatch.capture());
        assertThat(List.of(
                reservationChange.getValue().asOf(), waitingChange.getValue().asOf(),
                menuBatch.getValue().asOf(), paymentBatch.getValue().asOf()))
                .containsOnly(NOW);
    }

    @Test
    void returnsSuccessfulBaseRowsAsPartialWithoutCursorWhenTheOtherBaseFails() {
        willThrow(new ServiceException(ReservationErrorCode.RESERVATION_MONITORING_UNAVAILABLE))
                .given(reservations).findChangedCases(any());
        willReturn(new WaitingMonitoringContracts.ReferencePage(
                List.of(new WaitingMonitoringContracts.CaseReference("waiting:9", "7", CHANGED)), NOW, NOW))
                .given(waitings).findChangedCases(any());
        willReturn(waitingBatch("waiting:9")).given(waitings).findCases(any());

        CasePage page = service.list(PRINCIPAL, query(20));

        assertThat(page.items()).extracting(item -> item.caseId()).containsExactly("waiting:9");
        assertThat(page.completeness()).isEqualTo(PARTIAL);
        assertThat(page.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.source().name()).isEqualTo("RESERVATION"));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void failsTheRequestWhenEveryRequestedBaseSourceFails() {
        willThrow(new ServiceException(ReservationErrorCode.RESERVATION_MONITORING_UNAVAILABLE))
                .given(reservations).findChangedCases(any());
        willThrow(new ServiceException(ReservationErrorCode.WAITING_MONITORING_UNAVAILABLE))
                .given(waitings).findChangedCases(any());

        assertThatThrownBy(() -> service.list(PRINCIPAL, query(20)))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AdminMonitoringErrorCode.MONITORING_SOURCES_UNAVAILABLE));
    }

    @Test
    void failsWhenTheOnlyRequestedBaseSourceFails() {
        willThrow(new ServiceException(ReservationErrorCode.RESERVATION_MONITORING_UNAVAILABLE))
                .given(reservations).findChangedCases(any());

        assertError(
                () -> service.list(PRINCIPAL, reservationQuery()),
                AdminMonitoringErrorCode.MONITORING_SOURCES_UNAVAILABLE);
    }

    @Test
    void isolatesAncillaryFailureWithoutOverwritingReservationLifecycle() {
        willReturn(new ReservationMonitoringContracts.ReferencePage(
                List.of(new ReservationMonitoringContracts.CaseReference(
                        "reservation-hold:12", "7", CHANGED)), NOW, NOW))
                .given(reservations).findChangedCases(any());
        willReturn(reservationBatch("reservation-hold:12")).given(reservations).findCases(any());
        willThrow(new ServiceException(MenuHoldErrorCode.MONITORING_SOURCE_UNAVAILABLE))
                .given(menuHolds).findCases(any());

        CasePage page = service.list(PRINCIPAL, reservationQuery());

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.lifecycleStatus().name()).isEqualTo("CONFIRMED");
            assertThat(item.ledgers()).filteredOn(ledger -> ledger.source() == MENU_HOLD)
                    .singleElement().satisfies(ledger -> {
                        assertThat(ledger.present()).isFalse();
                        assertThat(ledger.completeness().name()).isEqualTo("UNAVAILABLE");
                    });
        });
        assertThat(page.completeness()).isEqualTo(PARTIAL);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void rowDataThroughIncludesDelayedPaymentInsteadOfClaimingPrimaryFreshness() {
        Instant paymentThrough = NOW.minusSeconds(10);
        willReturn(new ReservationMonitoringContracts.ReferencePage(
                List.of(new ReservationMonitoringContracts.CaseReference(
                        "reservation-hold:12", "7", CHANGED)), NOW, NOW))
                .given(reservations).findChangedCases(any());
        willReturn(reservationBatch("reservation-hold:12")).given(reservations).findCases(any());
        var payment = new PaymentMonitoringContracts.SourceCell(
                "reservation-hold:12",
                new PaymentMonitoringContracts.ConfirmedState(
                        "81", "PAID", 1L, CHANGED, 10_000L, 0L, "KRW"),
                NOW, paymentThrough, PaymentMonitoringContracts.Completeness.DELAYED,
                PaymentMonitoringContracts.ReconciliationStatus.MATCHED, CHANGED);
        willReturn(new PaymentMonitoringContracts.BatchResult(List.of(payment), NOW, paymentThrough))
                .given(payments).findCases(any());

        CasePage page = service.list(PRINCIPAL, reservationQuery());

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.dataThrough()).isEqualTo(paymentThrough);
            assertThat(item.completeness().name()).isEqualTo("DELAYED");
        });
    }

    @Test
    void reservationOnlyCheckInLookupFailureCannotMasqueradeAsAnEmptyPartialPage() {
        willReturn(new ReservationMonitoringContracts.ReferencePage(
                List.of(new ReservationMonitoringContracts.CaseReference(
                        "reservation-hold:12", "7", CHANGED)), NOW, NOW))
                .given(reservations).findChangedCases(any());
        var stalePrimary = new ReservationMonitoringContracts.CaseSnapshot(
                "reservation-hold:12", "7", 2, NOW.plusSeconds(3600), NOW.plusSeconds(7200),
                List.of(new ReservationMonitoringContracts.LedgerCell(
                        "RESERVATION", "CONFIRMED", 0L, CHANGED.minusSeconds(1),
                        NOW, NOW, ReservationMonitoringContracts.Completeness.COMPLETE,
                        ReservationMonitoringContracts.ReconciliationStatus.MATCHED)));
        willReturn(new ReservationMonitoringContracts.BatchResult(List.of(stalePrimary), NOW, NOW))
                .given(reservations).findCases(any());
        willThrow(new ServiceException(ReservationErrorCode.RESERVATION_MONITORING_UNAVAILABLE))
                .given(reservations).findCase(any());

        assertError(
                () -> service.list(PRINCIPAL, reservationQuery()),
                AdminMonitoringErrorCode.MONITORING_SOURCES_UNAVAILABLE);
    }

    @Test
    void composesAssignedReservationDetailWithCheckInMenuAndBoundedPaymentHistory() {
        var snapshot = reservationSnapshot("reservation-hold:12");
        willReturn(Optional.of(new ReservationMonitoringContracts.Detail(
                snapshot,
                List.of(
                        new ReservationMonitoringContracts.Event(
                                "RESERVATION", "CREATED", 0L, null, "CONFIRMED", CHANGED.minusSeconds(10)),
                        new ReservationMonitoringContracts.Event(
                                "RESERVATION", "CHECKED_IN", 0L, "CONFIRMED", "CONFIRMED", CHANGED)))))
                .given(reservations).findCase(any());
        willReturn(Optional.of(menuDetail("reservation-hold:12"))).given(menuHolds).findCase(any());
        willReturn(Optional.of(paymentDetail("reservation-hold:12"))).given(payments).findCase(any());

        var detail = service.get(PRINCIPAL, RESERVATION, "reservation-hold:12", NOW);

        assertThat(detail.lifecycleStatus()).isEqualTo(CHECKED_IN);
        assertThat(detail.maskingLevel().name()).isEqualTo("MINIMIZED");
        assertThat(detail.menuItems()).singleElement()
                .satisfies(item -> assertThat(item.displayName()).isEqualTo("라자냐"));
        assertThat(detail.paymentLedger()).singleElement()
                .satisfies(event -> assertThat(event.amountMinor()).isEqualTo(10_000L));
        assertThat(detail.refunds()).singleElement()
                .satisfies(refund -> assertThat(refund.sourceStatus()).isEqualTo("COMPLETED"));
        then(authorization).should().requireDetail(PRINCIPAL, "reservation-hold:12", 1L);
    }

    @Test
    void distinguishesPrimarySourceFailureFromConfirmedAbsence() {
        willThrow(new ServiceException(ReservationErrorCode.RESERVATION_MONITORING_UNAVAILABLE))
                .given(reservations).findCase(any());
        assertError(
                () -> service.get(PRINCIPAL, RESERVATION, "reservation-hold:12", NOW),
                AdminMonitoringErrorCode.MONITORING_SOURCES_UNAVAILABLE);

        willReturn(Optional.empty()).given(reservations).findCase(any());
        assertError(
                () -> service.get(PRINCIPAL, RESERVATION, "reservation-hold:12", NOW),
                AdminMonitoringErrorCode.MONITORING_CASE_NOT_FOUND);
    }

    private void emptySourceDefaults() {
        given(reservations.findChangedCases(any())).willAnswer(invocation -> {
            var query = invocation.getArgument(0, ReservationMonitoringContracts.ChangeQuery.class);
            return new ReservationMonitoringContracts.ReferencePage(List.of(), query.asOf(), query.asOf());
        });
        given(waitings.findChangedCases(any())).willAnswer(invocation -> {
            var query = invocation.getArgument(0, WaitingMonitoringContracts.ChangeQuery.class);
            return new WaitingMonitoringContracts.ReferencePage(List.of(), query.asOf(), query.asOf());
        });
        given(reservations.findCases(any())).willAnswer(invocation -> {
            var query = invocation.getArgument(0, ReservationMonitoringContracts.BatchQuery.class);
            return new ReservationMonitoringContracts.BatchResult(List.of(), query.asOf(), query.asOf());
        });
        given(waitings.findCases(any())).willAnswer(invocation -> {
            var query = invocation.getArgument(0, WaitingMonitoringContracts.BatchQuery.class);
            return new WaitingMonitoringContracts.BatchResult(List.of(), query.asOf(), query.asOf());
        });
        given(menuHolds.findCases(any())).willAnswer(invocation -> {
            var query = invocation.getArgument(0, MenuHoldMonitoringContracts.BatchQuery.class);
            return new MenuHoldMonitoringContracts.BatchResult(List.of(), query.asOf(), query.asOf());
        });
        given(payments.findCases(any())).willAnswer(invocation -> {
            var query = invocation.getArgument(0, PaymentMonitoringContracts.BatchQuery.class);
            return new PaymentMonitoringContracts.BatchResult(List.of(), query.asOf(), query.asOf());
        });
    }

    private static ListQuery query(int size) {
        return new ListQuery(
                "7", Set.of(RESERVATION, WAITING), Set.of(), Set.of(), Set.of(),
                NOW.minus(Duration.ofDays(1)), NOW, size, null);
    }

    private static ListQuery reservationQuery() {
        return new ListQuery(
                "7", Set.of(RESERVATION), Set.of(), Set.of(), Set.of(),
                NOW.minus(Duration.ofDays(1)), NOW, 20, null);
    }

    private static ReservationMonitoringContracts.BatchResult reservationBatch(String caseId) {
        return new ReservationMonitoringContracts.BatchResult(
                List.of(reservationSnapshot(caseId)), NOW, NOW);
    }

    private static ReservationMonitoringContracts.CaseSnapshot reservationSnapshot(String caseId) {
        return new ReservationMonitoringContracts.CaseSnapshot(
                caseId, "7", 2, NOW.plusSeconds(3600), NOW.plusSeconds(7200),
                List.of(
                        new ReservationMonitoringContracts.LedgerCell(
                                "RESERVATION_HOLD", "CONFIRMED", 1L, CHANGED.minusSeconds(1),
                                NOW, NOW, ReservationMonitoringContracts.Completeness.COMPLETE,
                                ReservationMonitoringContracts.ReconciliationStatus.MATCHED),
                        new ReservationMonitoringContracts.LedgerCell(
                                "RESERVATION", "CONFIRMED", 0L, CHANGED,
                                NOW, NOW, ReservationMonitoringContracts.Completeness.COMPLETE,
                                ReservationMonitoringContracts.ReconciliationStatus.MATCHED)));
    }

    private static WaitingMonitoringContracts.BatchResult waitingBatch(String caseId) {
        return new WaitingMonitoringContracts.BatchResult(List.of(new WaitingMonitoringContracts.SourceCell(
                caseId,
                new WaitingMonitoringContracts.ConfirmedState(
                        "7", "WAITING", 2L, CHANGED, 3, 8L,
                        new WaitingMonitoringContracts.Links(null, null)),
                NOW, NOW, WaitingMonitoringContracts.Completeness.COMPLETE,
                WaitingMonitoringContracts.ReconciliationStatus.MATCHED, CHANGED)), NOW, NOW);
    }

    private static MenuHoldMonitoringContracts.Detail menuDetail(String caseId) {
        var cell = new MenuHoldMonitoringContracts.SourceCell(
                caseId, "7", new MenuHoldMonitoringContracts.ConfirmedState("CONFIRMED", 2L, CHANGED),
                NOW, NOW, MenuHoldMonitoringContracts.Completeness.COMPLETE,
                MenuHoldMonitoringContracts.ReconciliationStatus.MATCHED, CHANGED,
                new MenuHoldMonitoringContracts.Links(null, "12"));
        return new MenuHoldMonitoringContracts.Detail(
                cell,
                List.of(new MenuHoldMonitoringContracts.Transition(2L, "ACTIVE", "CONFIRMED", CHANGED)),
                List.of(new MenuHoldMonitoringContracts.Item("5", "라자냐", 1)));
    }

    private static PaymentMonitoringContracts.Detail paymentDetail(String caseId) {
        var cell = new PaymentMonitoringContracts.SourceCell(
                caseId,
                new PaymentMonitoringContracts.ConfirmedState(
                        "81", "PARTIALLY_REFUNDED", 3L, CHANGED,
                        10_000L, 2_000L, "KRW"),
                NOW, NOW, PaymentMonitoringContracts.Completeness.COMPLETE,
                PaymentMonitoringContracts.ReconciliationStatus.MATCHED, CHANGED);
        return new PaymentMonitoringContracts.Detail(
                cell,
                List.of(new PaymentMonitoringContracts.LedgerEvent("PAID", 10_000L, CHANGED)),
                false,
                List.of(new PaymentMonitoringContracts.Refund(
                        "COMPLETED", 1L, 2_000L, CHANGED, CHANGED.plusSeconds(1))),
                false);
    }

    private static void assertError(Runnable action, AdminMonitoringErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
