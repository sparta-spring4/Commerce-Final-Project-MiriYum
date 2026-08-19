package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.payment.dto.PaymentContracts.PaymentAttemptStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.StoreReservationPaymentSnapshot;
import com.miriyum.domain.payment.dto.PaymentContracts.StoreReservationRefundSnapshot;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.dto.response.StoreReservationPaymentStatusResponse;
import com.miriyum.domain.reservation.dto.response.StoreReservationPaymentStatusResponse.StorePaymentResult;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationDepositProcessStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository.DepositProcessLink;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreReservationPaymentStatusQueryServiceTest {

    private static final long OPERATOR_ID = 33L;
    private static final long STORE_ID = 22L;
    private static final long RESERVATION_ID = 77L;
    private static final String PAYMENT_ID = "900000000000000001";
    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");

    @Mock
    private StoreService storeService;
    @Mock
    private ReservationRepository reservations;
    @Mock
    private ReservationDepositProcessRepository depositProcesses;
    @Mock
    private PaymentService paymentService;
    @Mock
    private Reservation reservation;
    @Mock
    private DepositProcessLink processLink;

    private StoreReservationPaymentStatusQueryService service;

    @BeforeEach
    void setUp() {
        service = new StoreReservationPaymentStatusQueryService(
                storeService,
                reservations,
                depositProcesses,
                paymentService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void returnsNotApplicableForV1WithoutReadingPayment() {
        ownedReservation(1L);

        StoreReservationPaymentStatusResponse response =
                service.get(OPERATOR_ID, STORE_ID, RESERVATION_ID);

        assertThat(response.reservationId()).isEqualTo(Long.toString(RESERVATION_ID));
        assertThat(response.result()).isEqualTo(StorePaymentResult.NOT_APPLICABLE);
        assertThat(response.reconciliationRequired()).isFalse();
        assertThat(response.observedAt()).isEqualTo(NOW);
        assertThat(response.payment()).isNull();
        then(depositProcesses).shouldHaveNoInteractions();
        then(paymentService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @MethodSource("resultCases")
    void projectsCanonicalStoredStatesWithRequiredPrecedence(
            StoreReservationPaymentSnapshot snapshot,
            StorePaymentResult expected
    ) {
        completedV2Link();
        given(paymentService.findReservationDepositPayment(PAYMENT_ID))
                .willReturn(Optional.of(snapshot));

        StoreReservationPaymentStatusResponse response =
                service.get(OPERATOR_ID, STORE_ID, RESERVATION_ID);

        assertThat(response.result()).isEqualTo(expected);
        assertThat(response.reconciliationRequired())
                .isEqualTo(expected == StorePaymentResult.UNKNOWN);
        assertThat(response.payment().paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(response.payment().amountMinor()).isEqualTo(30_000L);
        assertThat(response.payment().currency()).isEqualTo("KRW");
        assertThat(response.payment().status()).isEqualTo(snapshot.status());
        assertThat(response.payment().lastAttemptStatus())
                .isEqualTo(snapshot.lastAttemptStatus());
        assertThat(response.payment().refunds()).hasSize(snapshot.refunds().size());
    }

    @Test
    void hidesForeignStoreBeforeReservationOrPaymentLookup() {
        org.mockito.BDDMockito.willThrow(new ServiceException(StoreErrorCode.STORE_NOT_FOUND))
                .given(storeService)
                .requireConcealedReadOwnership(OPERATOR_ID, STORE_ID);

        assertThatThrownBy(() -> service.get(OPERATOR_ID, STORE_ID, RESERVATION_ID))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.STORE_NOT_FOUND));

        then(reservations).shouldHaveNoInteractions();
        then(depositProcesses).shouldHaveNoInteractions();
        then(paymentService).shouldHaveNoInteractions();
    }

    @Test
    void hidesReservationFromAnotherStoreAsReservationNotFound() {
        given(reservations.findByIdAndStoreId(RESERVATION_ID, STORE_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(OPERATOR_ID, STORE_ID, RESERVATION_ID))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND));

        then(paymentService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @MethodSource("invalidLinks")
    void mapsInvalidV2ProcessLinkToServiceUnavailable(
            Optional<DepositProcessLink> link
    ) {
        ownedReservation(2L);
        given(depositProcesses.findDepositProcessLinkByFinalReservationId(RESERVATION_ID))
                .willReturn(link);

        assertUnavailable();
        then(paymentService).shouldHaveNoInteractions();
    }

    @Test
    void mapsMissingOrWrongSourcePaymentToServiceUnavailable() {
        completedV2Link();
        given(paymentService.findReservationDepositPayment(PAYMENT_ID))
                .willReturn(Optional.empty());

        assertUnavailable();
    }

    @Test
    void rejectsStoredStateCombinationOutsideTheProjectionContract() {
        completedV2Link();
        given(paymentService.findReservationDepositPayment(PAYMENT_ID))
                .willReturn(Optional.of(snapshot(
                        PaymentStatus.READY,
                        PaymentAttemptStatus.PAID,
                        List.of())));

        assertUnavailable();
    }

    private void ownedReservation(long cancellationPolicyVersion) {
        given(reservations.findByIdAndStoreId(RESERVATION_ID, STORE_ID))
                .willReturn(Optional.of(reservation));
        given(reservation.getCancellationPolicyVersion())
                .willReturn(cancellationPolicyVersion);
    }

    private void completedV2Link() {
        ownedReservation(2L);
        given(depositProcesses.findDepositProcessLinkByFinalReservationId(RESERVATION_ID))
                .willReturn(Optional.of(processLink));
        given(processLink.getStatus()).willReturn(ReservationDepositProcessStatus.COMPLETED);
        given(processLink.getFinalReservationId()).willReturn(RESERVATION_ID);
        given(processLink.getPaymentId()).willReturn(PAYMENT_ID);
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> service.get(OPERATOR_ID, STORE_ID, RESERVATION_ID))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }

    private static Stream<Arguments> resultCases() {
        return Stream.of(
                Arguments.of(snapshot(
                        PaymentStatus.READY,
                        PaymentAttemptStatus.NOT_STARTED,
                        List.of()), StorePaymentResult.AWAITING_PAYMENT),
                Arguments.of(snapshot(
                        PaymentStatus.PAID,
                        PaymentAttemptStatus.PAID,
                        List.of(refund(RefundStatus.PROCESSING))), StorePaymentResult.PROCESSING),
                Arguments.of(snapshot(
                        PaymentStatus.READY,
                        PaymentAttemptStatus.FAILED,
                        List.of()), StorePaymentResult.FAILED),
                Arguments.of(snapshot(
                        PaymentStatus.PARTIALLY_REFUNDED,
                        PaymentAttemptStatus.PAID,
                        List.of(refund(RefundStatus.COMPLETED))), StorePaymentResult.COMPLETED),
                Arguments.of(snapshot(
                        PaymentStatus.CONFIRMING,
                        PaymentAttemptStatus.UNKNOWN,
                        List.of(refund(RefundStatus.FAILED))), StorePaymentResult.UNKNOWN),
                Arguments.of(snapshot(
                        PaymentStatus.PAID,
                        PaymentAttemptStatus.FAILED,
                        List.of(refund(RefundStatus.PROCESSING))), StorePaymentResult.PROCESSING),
                Arguments.of(snapshot(
                        PaymentStatus.PAID,
                        PaymentAttemptStatus.PAID,
                        List.of(refund(RefundStatus.RECONCILIATION_REQUIRED))),
                        StorePaymentResult.UNKNOWN));
    }

    private static Stream<Arguments> invalidLinks() {
        DepositProcessLink wrongStatus = org.mockito.Mockito.mock(DepositProcessLink.class);
        given(wrongStatus.getStatus()).willReturn(ReservationDepositProcessStatus.AWAITING_PAYMENT);
        given(wrongStatus.getFinalReservationId()).willReturn(RESERVATION_ID);
        given(wrongStatus.getPaymentId()).willReturn(PAYMENT_ID);
        DepositProcessLink wrongReservation = org.mockito.Mockito.mock(DepositProcessLink.class);
        given(wrongReservation.getStatus()).willReturn(ReservationDepositProcessStatus.COMPLETED);
        given(wrongReservation.getFinalReservationId()).willReturn(88L);
        given(wrongReservation.getPaymentId()).willReturn(PAYMENT_ID);
        DepositProcessLink missingPayment = org.mockito.Mockito.mock(DepositProcessLink.class);
        given(missingPayment.getStatus()).willReturn(ReservationDepositProcessStatus.COMPLETED);
        given(missingPayment.getFinalReservationId()).willReturn(RESERVATION_ID);
        given(missingPayment.getPaymentId()).willReturn(null);
        return Stream.of(
                Arguments.of(Optional.empty()),
                Arguments.of(Optional.of(wrongStatus)),
                Arguments.of(Optional.of(wrongReservation)),
                Arguments.of(Optional.of(missingPayment)));
    }

    private static StoreReservationPaymentSnapshot snapshot(
            PaymentStatus status,
            PaymentAttemptStatus attemptStatus,
            List<StoreReservationRefundSnapshot> refunds
    ) {
        return new StoreReservationPaymentSnapshot(
                PAYMENT_ID,
                30_000L,
                status == PaymentStatus.PARTIALLY_REFUNDED ? 10_000L : 0L,
                status == PaymentStatus.PARTIALLY_REFUNDED ? 20_000L : 30_000L,
                "KRW",
                status,
                attemptStatus,
                NOW.minusSeconds(120),
                status == PaymentStatus.READY || status == PaymentStatus.CONFIRMING
                        ? null : NOW.minusSeconds(90),
                NOW,
                refunds);
    }

    private static StoreReservationRefundSnapshot refund(RefundStatus status) {
        return new StoreReservationRefundSnapshot(
                "910000000000000001",
                10_000L,
                status,
                NOW.minusSeconds(30),
                status == RefundStatus.COMPLETED ? NOW : null);
    }
}
