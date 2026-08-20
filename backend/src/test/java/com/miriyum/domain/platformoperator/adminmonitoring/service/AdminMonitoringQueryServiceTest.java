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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import com.miriyum.domain.menuhold.dto.MenuHoldMonitoringContracts;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.service.MenuHoldMonitoringQueryService;
import com.miriyum.domain.payment.dto.PaymentMonitoringContracts;
import com.miriyum.domain.payment.service.PaymentMonitoringQueryService;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringRequests.ListQuery;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CasePage;
import com.miriyum.domain.platformoperator.adminmonitoring.exception.AdminMonitoringErrorCode;
import com.miriyum.domain.platformoperator.adminmonitoring.service.AdminMonitoringCursorCodec.CursorState;
import com.miriyum.domain.platformoperator.adminmonitoring.service.AdminMonitoringCursorCodec.SourceSeek;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
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
        ArgumentCaptor<MenuHoldMonitoringContracts.ChangeQuery> menuChange =
                ArgumentCaptor.forClass(MenuHoldMonitoringContracts.ChangeQuery.class);
        ArgumentCaptor<PaymentMonitoringContracts.ChangeQuery> paymentChange =
                ArgumentCaptor.forClass(PaymentMonitoringContracts.ChangeQuery.class);
        ArgumentCaptor<MenuHoldMonitoringContracts.BatchQuery> menuBatch =
                ArgumentCaptor.forClass(MenuHoldMonitoringContracts.BatchQuery.class);
        ArgumentCaptor<PaymentMonitoringContracts.BatchQuery> paymentBatch =
                ArgumentCaptor.forClass(PaymentMonitoringContracts.BatchQuery.class);
        then(reservations).should().findChangedCases(reservationChange.capture());
        then(waitings).should().findChangedCases(waitingChange.capture());
        then(menuHolds).should().findChangedCases(menuChange.capture());
        then(payments).should().findChangedCases(paymentChange.capture());
        then(menuHolds).should().findCases(menuBatch.capture());
        then(payments).should().findCases(paymentBatch.capture());
        assertThat(List.of(
                reservationChange.getValue().asOf(), waitingChange.getValue().asOf(),
                menuChange.getValue().asOf(), paymentChange.getValue().asOf(),
                menuBatch.getValue().asOf(), paymentBatch.getValue().asOf()))
                .containsOnly(NOW);
    }

    @Test
    void fixesTheFirstPageSnapshotAtChangedToInsteadOfRequestTime() {
        Instant changedTo = NOW.minusSeconds(60);
        ListQuery query = new ListQuery(
                "7", Set.of(RESERVATION, WAITING), Set.of(), Set.of(), Set.of(),
                NOW.minus(Duration.ofDays(1)), changedTo, 20, null);

        CasePage page = service.list(PRINCIPAL, query);

        assertThat(page.asOf()).isEqualTo(changedTo);
        ArgumentCaptor<ReservationMonitoringContracts.ChangeQuery> reservation =
                ArgumentCaptor.forClass(ReservationMonitoringContracts.ChangeQuery.class);
        ArgumentCaptor<WaitingMonitoringContracts.ChangeQuery> waiting =
                ArgumentCaptor.forClass(WaitingMonitoringContracts.ChangeQuery.class);
        ArgumentCaptor<MenuHoldMonitoringContracts.ChangeQuery> menu =
                ArgumentCaptor.forClass(MenuHoldMonitoringContracts.ChangeQuery.class);
        ArgumentCaptor<PaymentMonitoringContracts.ChangeQuery> payment =
                ArgumentCaptor.forClass(PaymentMonitoringContracts.ChangeQuery.class);
        then(reservations).should().findChangedCases(reservation.capture());
        then(waitings).should().findChangedCases(waiting.capture());
        then(menuHolds).should().findChangedCases(menu.capture());
        then(payments).should().findChangedCases(payment.capture());
        assertThat(List.of(
                reservation.getValue().asOf(), waiting.getValue().asOf(),
                menu.getValue().asOf(), payment.getValue().asOf()))
                .containsOnly(changedTo);
    }

    @Test
    void refillsAFullPaymentTieBeforeChoosingTheGlobalCaseTypeBoundary() {
        List<PaymentMonitoringContracts.CaseReference> firstPaymentPage =
                IntStream.rangeClosed(101, 200)
                        .mapToObj(index -> new PaymentMonitoringContracts.CaseReference(
                                "waiting:" + index, CHANGED))
                        .toList();
        willAnswer(invocation -> {
            var change = invocation.getArgument(0, PaymentMonitoringContracts.ChangeQuery.class);
            return change.after() == null
                    ? new PaymentMonitoringContracts.ReferencePage(firstPaymentPage, NOW, NOW)
                    : new PaymentMonitoringContracts.ReferencePage(
                            List.of(new PaymentMonitoringContracts.CaseReference(
                                    "reservation:999", CHANGED)), NOW, NOW);
        }).given(payments).findChangedCases(any());
        willAnswer(invocation -> {
            var batch = invocation.getArgument(0, ReservationMonitoringContracts.BatchQuery.class);
            return new ReservationMonitoringContracts.BatchResult(
                    batch.caseIds().stream().map(AdminMonitoringQueryServiceTest::reservationSnapshot).toList(),
                    NOW, NOW);
        }).given(reservations).findCases(any());
        willAnswer(invocation -> {
            var batch = invocation.getArgument(0, WaitingMonitoringContracts.BatchQuery.class);
            return new WaitingMonitoringContracts.BatchResult(
                    batch.caseIds().stream().map(AdminMonitoringQueryServiceTest::waitingCell).toList(),
                    NOW, NOW);
        }).given(waitings).findCases(any());
        willAnswer(invocation -> {
            var batch = invocation.getArgument(0, PaymentMonitoringContracts.BatchQuery.class);
            return paymentBatch(batch.caseIds(), CHANGED);
        }).given(payments).findCases(any());

        CasePage page = service.list(PRINCIPAL, query(1));

        assertThat(page.items()).extracting(item -> item.caseId())
                .containsExactly("reservation:999");
        then(payments).should(times(2)).findChangedCases(any());
    }

    @Test
    void reportsPartialWithoutCursorWhenTheTieExceedsTheBoundedSourceScan() {
        AtomicInteger pages = new AtomicInteger();
        willAnswer(invocation -> {
            int page = pages.getAndIncrement();
            List<PaymentMonitoringContracts.CaseReference> references =
                    IntStream.rangeClosed(1, 100)
                            .mapToObj(index -> new PaymentMonitoringContracts.CaseReference(
                                    "waiting:" + (page * 100 + index), CHANGED))
                            .toList();
            return new PaymentMonitoringContracts.ReferencePage(references, NOW, NOW);
        }).given(payments).findChangedCases(any());
        willAnswer(invocation -> {
            var batch = invocation.getArgument(0, WaitingMonitoringContracts.BatchQuery.class);
            return new WaitingMonitoringContracts.BatchResult(
                    batch.caseIds().stream().map(AdminMonitoringQueryServiceTest::waitingCell).toList(),
                    NOW, NOW);
        }).given(waitings).findCases(any());
        willAnswer(invocation -> {
            var batch = invocation.getArgument(0, PaymentMonitoringContracts.BatchQuery.class);
            return paymentBatch(batch.caseIds(), CHANGED);
        }).given(payments).findCases(any());

        ListQuery filtered = new ListQuery(
                "7", Set.of(RESERVATION, WAITING), Set.of(), Set.of("PAYMENT:PAID"), Set.of(),
                NOW.minus(Duration.ofDays(1)), NOW, 1, null);

        CasePage page = service.list(PRINCIPAL, filtered);

        assertThat(page.items()).singleElement().satisfies(item ->
                assertThat(item.ledgers()).filteredOn(ledger -> ledger.source().name().equals("PAYMENT"))
                        .singleElement().satisfies(ledger -> {
                            assertThat(ledger.present()).isTrue();
                            assertThat(ledger.completeness().name()).isEqualTo("COMPLETE");
                        }));
        assertThat(page.completeness()).isEqualTo(PARTIAL);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.source().name()).isEqualTo("PAYMENT");
            assertThat(failure.errorCode()).isEqualTo("MONITORING_005");
        });
        then(payments).should(times(10)).findChangedCases(any());
    }

    @Test
    void suppressesAnOlderCrossSourceDuplicateWithoutCursorIdHistory() {
        Instant paymentChanged = NOW.minusSeconds(5);
        Instant secondReservationChanged = NOW.minusSeconds(10);
        Instant duplicateReservationChanged = NOW.minusSeconds(20);
        willReturn(new ReservationMonitoringContracts.ReferencePage(
                List.of(
                        new ReservationMonitoringContracts.CaseReference(
                                "reservation:2", "7", secondReservationChanged),
                        new ReservationMonitoringContracts.CaseReference(
                                "reservation:1", "7", duplicateReservationChanged)),
                NOW, NOW)).given(reservations).findChangedCases(any());
        willAnswer(invocation -> {
            var change = invocation.getArgument(0, PaymentMonitoringContracts.ChangeQuery.class);
            return new PaymentMonitoringContracts.ReferencePage(
                    change.after() == null
                            ? List.of(new PaymentMonitoringContracts.CaseReference(
                                    "reservation:1", paymentChanged))
                            : List.of(),
                    NOW, NOW);
        }).given(payments).findChangedCases(any());
        willAnswer(invocation -> {
            var batch = invocation.getArgument(0, ReservationMonitoringContracts.BatchQuery.class);
            return new ReservationMonitoringContracts.BatchResult(
                    batch.caseIds().stream().map(AdminMonitoringQueryServiceTest::reservationSnapshot).toList(),
                    NOW, NOW);
        }).given(reservations).findCases(any());
        willReturn(paymentBatch("reservation:1", paymentChanged))
                .given(payments).findCases(any());
        ListQuery firstQuery = new ListQuery(
                "7", Set.of(RESERVATION), Set.of(), Set.of(), Set.of(),
                NOW.minus(Duration.ofDays(1)), NOW, 1, null);
        willReturn("cursor").given(cursors).encode(any(), eq(firstQuery));

        CasePage first = service.list(PRINCIPAL, firstQuery);
        ArgumentCaptor<CursorState> encoded = ArgumentCaptor.forClass(CursorState.class);
        then(cursors).should().encode(encoded.capture(), eq(firstQuery));
        ListQuery secondQuery = new ListQuery(
                "7", Set.of(RESERVATION), Set.of(), Set.of(), Set.of(),
                NOW.minus(Duration.ofDays(1)), NOW, 1, "cursor");
        willReturn(encoded.getValue()).given(cursors).decode("cursor", secondQuery);

        CasePage second = service.list(PRINCIPAL, secondQuery);

        assertThat(first.items()).extracting(item -> item.caseId())
                .containsExactly("reservation:1");
        assertThat(second.items()).extracting(item -> item.caseId())
                .containsExactly("reservation:2");
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void discoversCasesWhoseOnlyWindowChangeComesFromMenuHoldOrPayment() {
        Instant menuChanged = NOW.minusSeconds(10);
        Instant paymentChanged = NOW.minusSeconds(20);
        willReturn(new MenuHoldMonitoringContracts.ReferencePage(
                List.of(new MenuHoldMonitoringContracts.CaseReference(
                        "reservation-hold:12", "7", menuChanged)), NOW, NOW))
                .given(menuHolds).findChangedCases(any());
        willReturn(new PaymentMonitoringContracts.ReferencePage(
                List.of(new PaymentMonitoringContracts.CaseReference(
                        "waiting:9", paymentChanged)), NOW, NOW))
                .given(payments).findChangedCases(any());
        willReturn(reservationBatch("reservation-hold:12")).given(reservations).findCases(any());
        willReturn(waitingBatch("waiting:9")).given(waitings).findCases(any());
        willReturn(menuBatch("reservation-hold:12", menuChanged)).given(menuHolds).findCases(any());
        willReturn(paymentBatch("waiting:9", paymentChanged)).given(payments).findCases(any());

        CasePage page = service.list(PRINCIPAL, query(20));

        assertThat(page.items()).extracting(item -> item.caseId())
                .containsExactly("reservation-hold:12", "waiting:9");
        assertThat(page.items()).extracting(item -> item.statusChangedAt())
                .containsExactly(menuChanged, paymentChanged);
    }

    @Test
    void emitsOneCaseAtTheLatestChangeAcrossPrimaryAndLinkedStreams() {
        Instant menuChanged = NOW.minusSeconds(10);
        willReturn(new ReservationMonitoringContracts.ReferencePage(
                List.of(new ReservationMonitoringContracts.CaseReference(
                        "reservation-hold:12", "7", CHANGED)), NOW, NOW))
                .given(reservations).findChangedCases(any());
        willReturn(new MenuHoldMonitoringContracts.ReferencePage(
                List.of(new MenuHoldMonitoringContracts.CaseReference(
                        "reservation-hold:12", "7", menuChanged)), NOW, NOW))
                .given(menuHolds).findChangedCases(any());
        willReturn(reservationBatch("reservation-hold:12")).given(reservations).findCases(any());
        willReturn(menuBatch("reservation-hold:12", menuChanged)).given(menuHolds).findCases(any());

        CasePage page = service.list(PRINCIPAL, reservationQuery());

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.caseId()).isEqualTo("reservation-hold:12");
            assertThat(item.statusChangedAt()).isEqualTo(menuChanged);
        });
    }

    @Test
    void resumesAllFourChangeStreamsFromTheirOwnCursorCheckpoint() {
        ListQuery query = new ListQuery(
                "7", Set.of(RESERVATION, WAITING), Set.of(), Set.of(), Set.of(),
                NOW.minus(Duration.ofDays(1)), NOW, 20, "cursor");
        var reservationAfter = new SourceSeek(NOW.minusSeconds(11), "reservation:11");
        var waitingAfter = new SourceSeek(NOW.minusSeconds(12), "waiting:12");
        var menuAfter = new SourceSeek(NOW.minusSeconds(13), "reservation-hold:13");
        var paymentAfter = new SourceSeek(NOW.minusSeconds(14), "waiting:14");
        willReturn(new CursorState(
                NOW, null, reservationAfter, waitingAfter, menuAfter, paymentAfter))
                .given(cursors).decode("cursor", query);

        service.list(PRINCIPAL, query);

        ArgumentCaptor<ReservationMonitoringContracts.ChangeQuery> reservation =
                ArgumentCaptor.forClass(ReservationMonitoringContracts.ChangeQuery.class);
        ArgumentCaptor<WaitingMonitoringContracts.ChangeQuery> waiting =
                ArgumentCaptor.forClass(WaitingMonitoringContracts.ChangeQuery.class);
        ArgumentCaptor<MenuHoldMonitoringContracts.ChangeQuery> menu =
                ArgumentCaptor.forClass(MenuHoldMonitoringContracts.ChangeQuery.class);
        ArgumentCaptor<PaymentMonitoringContracts.ChangeQuery> payment =
                ArgumentCaptor.forClass(PaymentMonitoringContracts.ChangeQuery.class);
        then(reservations).should().findChangedCases(reservation.capture());
        then(waitings).should().findChangedCases(waiting.capture());
        then(menuHolds).should().findChangedCases(menu.capture());
        then(payments).should().findChangedCases(payment.capture());
        assertThat(reservation.getValue().after().caseId()).isEqualTo("reservation:11");
        assertThat(waiting.getValue().after().caseId()).isEqualTo("waiting:12");
        assertThat(menu.getValue().after().caseId()).isEqualTo("reservation-hold:13");
        assertThat(payment.getValue().after().caseId()).isEqualTo("waiting:14");
    }

    @Test
    void doesNotAdvanceAnOlderDuplicatePastUnevaluatedPrimaryCases() {
        Instant paymentChanged = NOW.minusSeconds(5);
        Instant secondReservationChanged = NOW.minusSeconds(10);
        Instant duplicatePrimaryChanged = NOW.minusSeconds(20);
        ListQuery query = new ListQuery(
                "7", Set.of(RESERVATION), Set.of(), Set.of(), Set.of(),
                NOW.minus(Duration.ofDays(1)), NOW, 1, null);
        willReturn(new ReservationMonitoringContracts.ReferencePage(
                List.of(
                        new ReservationMonitoringContracts.CaseReference(
                                "reservation:2", "7", secondReservationChanged),
                        new ReservationMonitoringContracts.CaseReference(
                                "reservation:1", "7", duplicatePrimaryChanged)),
                NOW, NOW)).given(reservations).findChangedCases(any());
        willReturn(new PaymentMonitoringContracts.ReferencePage(
                List.of(new PaymentMonitoringContracts.CaseReference(
                        "reservation:1", paymentChanged)), NOW, NOW))
                .given(payments).findChangedCases(any());
        willReturn(new ReservationMonitoringContracts.BatchResult(
                List.of(reservationSnapshot("reservation:1"), reservationSnapshot("reservation:2")),
                NOW, NOW)).given(reservations).findCases(any());
        willReturn(paymentBatch("reservation:1", paymentChanged)).given(payments).findCases(any());
        willReturn("next").given(cursors).encode(any(), eq(query));

        CasePage page = service.list(PRINCIPAL, query);

        assertThat(page.items()).extracting(item -> item.caseId())
                .containsExactly("reservation:1");
        ArgumentCaptor<CursorState> cursor = ArgumentCaptor.forClass(CursorState.class);
        then(cursors).should().encode(cursor.capture(), eq(query));
        assertThat(cursor.getValue().paymentAfter().caseId()).isEqualTo("reservation:1");
        assertThat(cursor.getValue().reservationAfter()).isNull();
    }

    @Test
    void internallyExhaustsAFullPageOfUnrequestedPaymentCaseTypes() {
        List<PaymentMonitoringContracts.CaseReference> waitingPayments = IntStream.rangeClosed(1, 100)
                .mapToObj(index -> new PaymentMonitoringContracts.CaseReference(
                        "waiting:" + index, NOW.minusSeconds(index)))
                .toList();
        willAnswer(invocation -> {
            var change = invocation.getArgument(0, PaymentMonitoringContracts.ChangeQuery.class);
            return new PaymentMonitoringContracts.ReferencePage(
                    change.after() == null ? waitingPayments : List.of(), NOW, NOW);
        }).given(payments).findChangedCases(any());
        ListQuery query = reservationQuery();

        CasePage page = service.list(PRINCIPAL, query);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        then(payments).should(times(2)).findChangedCases(any());
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
    void authorizesHistoricalReservationDetailWithTheCurrentPrimaryVersion() {
        Instant historicalAsOf = NOW.minusSeconds(120);
        var historical = reservationDetailAt("reservation:12", historicalAsOf, 0L);
        var current = reservationDetailAt("reservation:12", NOW, 5L);
        given(reservations.findCase(any())).willAnswer(invocation -> {
            var query = invocation.getArgument(0, ReservationMonitoringContracts.DetailQuery.class);
            return Optional.of(query.asOf().equals(NOW) ? current : historical);
        });

        var detail = service.get(PRINCIPAL, RESERVATION, "reservation:12", historicalAsOf);

        assertThat(detail.asOf()).isEqualTo(historicalAsOf);
        assertThat(detail.caseVersion()).isEqualTo(1L);
        then(authorization).should().requireDetail(PRINCIPAL, "reservation:12", 6L);
        then(reservations).should(times(2)).findCase(any());
    }

    @Test
    void authorizesHistoricalWaitingDetailWithTheCurrentPrimaryVersion() {
        Instant historicalAsOf = NOW.minusSeconds(120);
        var historical = waitingDetailAt("waiting:9", historicalAsOf, 2L);
        var current = waitingDetailAt("waiting:9", NOW, 7L);
        given(waitings.findCase(any())).willAnswer(invocation -> {
            var query = invocation.getArgument(0, WaitingMonitoringContracts.DetailQuery.class);
            return Optional.of(query.asOf().equals(NOW) ? current : historical);
        });

        var detail = service.get(PRINCIPAL, WAITING, "waiting:9", historicalAsOf);

        assertThat(detail.asOf()).isEqualTo(historicalAsOf);
        assertThat(detail.caseVersion()).isEqualTo(3L);
        then(authorization).should().requireDetail(PRINCIPAL, "waiting:9", 8L);
        then(waitings).should(times(2)).findCase(any());
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
        given(menuHolds.findChangedCases(any())).willAnswer(invocation -> {
            var query = invocation.getArgument(0, MenuHoldMonitoringContracts.ChangeQuery.class);
            return new MenuHoldMonitoringContracts.ReferencePage(List.of(), query.asOf(), query.asOf());
        });
        given(payments.findChangedCases(any())).willAnswer(invocation -> {
            var query = invocation.getArgument(0, PaymentMonitoringContracts.ChangeQuery.class);
            return new PaymentMonitoringContracts.ReferencePage(List.of(), query.asOf(), query.asOf());
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

    private static ReservationMonitoringContracts.Detail reservationDetailAt(
            String caseId,
            Instant asOf,
            long version
    ) {
        var snapshot = new ReservationMonitoringContracts.CaseSnapshot(
                caseId, "7", 2, NOW.plusSeconds(3600), NOW.plusSeconds(7200),
                List.of(new ReservationMonitoringContracts.LedgerCell(
                        "RESERVATION", "CONFIRMED", version, asOf.minusSeconds(1),
                        asOf, asOf, ReservationMonitoringContracts.Completeness.COMPLETE,
                        ReservationMonitoringContracts.ReconciliationStatus.MATCHED)));
        return new ReservationMonitoringContracts.Detail(snapshot, List.of());
    }

    private static WaitingMonitoringContracts.BatchResult waitingBatch(String caseId) {
        return new WaitingMonitoringContracts.BatchResult(List.of(waitingCell(caseId)), NOW, NOW);
    }

    private static WaitingMonitoringContracts.SourceCell waitingCell(String caseId) {
        return new WaitingMonitoringContracts.SourceCell(
                caseId,
                new WaitingMonitoringContracts.ConfirmedState(
                        "7", "WAITING", 2L, CHANGED, 3, 8L,
                        new WaitingMonitoringContracts.Links(null, null)),
                NOW, NOW, WaitingMonitoringContracts.Completeness.COMPLETE,
                WaitingMonitoringContracts.ReconciliationStatus.MATCHED, CHANGED);
    }

    private static WaitingMonitoringContracts.Detail waitingDetailAt(
            String caseId,
            Instant asOf,
            long version
    ) {
        var cell = new WaitingMonitoringContracts.SourceCell(
                caseId,
                new WaitingMonitoringContracts.ConfirmedState(
                        "7", "WAITING", version, asOf.minusSeconds(1), 3, 8L,
                        new WaitingMonitoringContracts.Links(null, null)),
                asOf, asOf, WaitingMonitoringContracts.Completeness.COMPLETE,
                WaitingMonitoringContracts.ReconciliationStatus.MATCHED, asOf.minusSeconds(1));
        return new WaitingMonitoringContracts.Detail(cell, List.of());
    }

    private static MenuHoldMonitoringContracts.BatchResult menuBatch(
            String caseId,
            Instant changedAt
    ) {
        var cell = new MenuHoldMonitoringContracts.SourceCell(
                caseId, "7", new MenuHoldMonitoringContracts.ConfirmedState(
                        "CONFIRMED", 2L, changedAt),
                NOW, NOW, MenuHoldMonitoringContracts.Completeness.COMPLETE,
                MenuHoldMonitoringContracts.ReconciliationStatus.MATCHED, changedAt,
                new MenuHoldMonitoringContracts.Links(null, "12"));
        return new MenuHoldMonitoringContracts.BatchResult(List.of(cell), NOW, NOW);
    }

    private static PaymentMonitoringContracts.BatchResult paymentBatch(
            String caseId,
            Instant changedAt
    ) {
        return paymentBatch(List.of(caseId), changedAt);
    }

    private static PaymentMonitoringContracts.BatchResult paymentBatch(
            List<String> caseIds,
            Instant changedAt
    ) {
        var cells = caseIds.stream().map(caseId -> new PaymentMonitoringContracts.SourceCell(
                        caseId,
                        new PaymentMonitoringContracts.ConfirmedState(
                                "81", "PAID", 1L, changedAt, 10_000L, 0L, "KRW"),
                        NOW, NOW, PaymentMonitoringContracts.Completeness.COMPLETE,
                        PaymentMonitoringContracts.ReconciliationStatus.MATCHED, changedAt))
                .toList();
        return new PaymentMonitoringContracts.BatchResult(cells, NOW, NOW);
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
