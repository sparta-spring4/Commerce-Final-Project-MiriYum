package com.miriyum.domain.reservation.service;

import com.miriyum.domain.payment.dto.PaymentContracts.PaymentAttemptStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.StoreReservationPaymentSnapshot;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.reservation.dto.response.StoreReservationPaymentStatusResponse;
import com.miriyum.domain.reservation.dto.response.StoreReservationPaymentStatusResponse.StorePaymentResult;
import com.miriyum.domain.reservation.dto.response.StoreReservationPaymentStatusResponse.StoreReservationPayment;
import com.miriyum.domain.reservation.dto.response.StoreReservationPaymentStatusResponse.StoreReservationRefund;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationDepositProcessStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository;
import com.miriyum.domain.reservation.repository.ReservationDepositProcessRepository.DepositProcessLink;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reservation이 Store 권한과 Payment 공개 snapshot을 조합하는 저장 전용 조회 경계다. */
@Service
public class StoreReservationPaymentStatusQueryService {

    private static final long DEPOSIT_POLICY_VERSION = 2L;
    private static final String PUBLIC_ID_PATTERN = "^[1-9][0-9]{0,18}$";
    private static final Set<PaymentStatus> COMPLETED_PAYMENT_STATUSES = Set.of(
            PaymentStatus.PAID,
            PaymentStatus.PARTIALLY_REFUNDED,
            PaymentStatus.REFUNDED);

    private final StoreService storeService;
    private final ReservationRepository reservations;
    private final ReservationDepositProcessRepository depositProcesses;
    private final PaymentService paymentService;
    private final Clock clock;

    public StoreReservationPaymentStatusQueryService(
            StoreService storeService,
            ReservationRepository reservations,
            ReservationDepositProcessRepository depositProcesses,
            PaymentService paymentService,
            Clock clock
    ) {
        this.storeService = storeService;
        this.reservations = reservations;
        this.depositProcesses = depositProcesses;
        this.paymentService = paymentService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StoreReservationPaymentStatusResponse get(
            long operatorAccountId,
            long storeId,
            long reservationId
    ) {
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        storeService.requireConcealedReadOwnership(operatorAccountId, storeId);
        Reservation reservation = reservations.findByIdAndStoreId(reservationId, storeId)
                .orElseThrow(() ->
                        new ServiceException(ReservationErrorCode.RESERVATION_NOT_FOUND));
        if (!Long.valueOf(DEPOSIT_POLICY_VERSION)
                .equals(reservation.getCancellationPolicyVersion())) {
            return new StoreReservationPaymentStatusResponse(
                    Long.toString(reservationId),
                    StorePaymentResult.NOT_APPLICABLE,
                    false,
                    observedAt,
                    null);
        }

        DepositProcessLink link = depositProcesses
                .findDepositProcessLinkByFinalReservationId(reservationId)
                .filter(candidate -> validLink(candidate, reservationId))
                .orElseThrow(StoreReservationPaymentStatusQueryService::unavailable);
        StoreReservationPaymentSnapshot snapshot = paymentService
                .findReservationDepositPayment(link.getPaymentId())
                .orElseThrow(StoreReservationPaymentStatusQueryService::unavailable);
        StorePaymentResult result = project(snapshot);
        return new StoreReservationPaymentStatusResponse(
                Long.toString(reservationId),
                result,
                result == StorePaymentResult.UNKNOWN,
                observedAt,
                toPayment(snapshot));
    }

    private static boolean validLink(DepositProcessLink link, long reservationId) {
        return link.getStatus() == ReservationDepositProcessStatus.COMPLETED
                && link.getFinalReservationId() != null
                && link.getFinalReservationId() == reservationId
                && link.getPaymentId() != null
                && link.getPaymentId().matches(PUBLIC_ID_PATTERN);
    }

    private static StorePaymentResult project(StoreReservationPaymentSnapshot payment) {
        if (payment.status() == PaymentStatus.RECONCILIATION_REQUIRED
                || payment.lastAttemptStatus() == PaymentAttemptStatus.UNKNOWN
                || payment.refunds().stream().anyMatch(refund ->
                        refund.status() == RefundStatus.RECONCILIATION_REQUIRED)) {
            return StorePaymentResult.UNKNOWN;
        }
        if (payment.status() == PaymentStatus.CONFIRMING
                || payment.lastAttemptStatus() == PaymentAttemptStatus.PENDING
                || payment.refunds().stream().anyMatch(refund ->
                        refund.status() == RefundStatus.REQUESTED
                                || refund.status() == RefundStatus.VALIDATING
                                || refund.status() == RefundStatus.PROCESSING)) {
            return StorePaymentResult.PROCESSING;
        }
        if (payment.lastAttemptStatus() == PaymentAttemptStatus.FAILED
                || payment.lastAttemptStatus() == PaymentAttemptStatus.CANCELLED
                || payment.refunds().stream().anyMatch(refund ->
                        refund.status() == RefundStatus.FAILED)) {
            return StorePaymentResult.FAILED;
        }
        if (payment.status() == PaymentStatus.READY
                && payment.lastAttemptStatus() == PaymentAttemptStatus.NOT_STARTED) {
            return StorePaymentResult.AWAITING_PAYMENT;
        }
        if (COMPLETED_PAYMENT_STATUSES.contains(payment.status())) {
            return StorePaymentResult.COMPLETED;
        }
        throw unavailable();
    }

    private static StoreReservationPayment toPayment(
            StoreReservationPaymentSnapshot payment
    ) {
        return new StoreReservationPayment(
                payment.paymentId(),
                payment.amountMinor(),
                payment.refundedAmountMinor(),
                payment.refundableAmountMinor(),
                payment.currency(),
                payment.status(),
                payment.lastAttemptStatus(),
                payment.createdAt(),
                payment.paidAt(),
                payment.updatedAt(),
                payment.refunds().stream()
                        .map(refund -> new StoreReservationRefund(
                                refund.refundId(),
                                refund.amountMinor(),
                                refund.status(),
                                refund.requestedAt(),
                                refund.completedAt()))
                        .toList());
    }

    private static ServiceException unavailable() {
        return new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
    }
}
