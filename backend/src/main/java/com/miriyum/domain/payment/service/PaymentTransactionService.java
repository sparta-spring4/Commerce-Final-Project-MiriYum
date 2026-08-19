package com.miriyum.domain.payment.service;

import com.miriyum.domain.payment.dto.PaymentContracts.ApplyReservationDepositDispositionCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.ConfirmPaymentCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionFailureClassification;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionResult;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.GetReservationDepositDispositionQuery;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistoryQuery;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistorySlice;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentAttemptStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareWaitingReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.VerifiedWaitingReservationDeposit;
import com.miriyum.domain.payment.config.PaymentSettings;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundSummary;
import com.miriyum.domain.payment.dto.PaymentContracts.StoreReservationPaymentSnapshot;
import com.miriyum.domain.payment.dto.PaymentContracts.StoreReservationRefundSnapshot;
import com.miriyum.domain.payment.entity.Payment;
import com.miriyum.domain.payment.entity.PaymentAttempt;
import com.miriyum.domain.payment.entity.PaymentLedgerEntry;
import com.miriyum.domain.payment.entity.PaymentLedgerEntry.Type;
import com.miriyum.domain.payment.entity.PaymentRefund;
import com.miriyum.domain.payment.entity.ReservationDepositDisposition;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderCancellation;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderPayment;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderStatus;
import com.miriyum.domain.payment.repository.PaymentAttemptRepository;
import com.miriyum.domain.payment.repository.PaymentLedgerEntryRepository;
import com.miriyum.domain.payment.repository.PaymentReferenceAllocator;
import com.miriyum.domain.payment.repository.PaymentRefundRepository;
import com.miriyum.domain.payment.repository.PaymentRepository;
import com.miriyum.domain.payment.repository.ReservationDepositDispositionRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 외부 PG 호출 전후의 짧은 DB transaction 경계를 정의한다. */
@Service
public class PaymentTransactionService {

    record ConfirmationClaim(
            boolean requiresProviderLookup,
            String paymentId,
            String portOnePaymentId,
            long amountMinor,
            String currency,
            String webhookMessageId,
            String webhookType,
            String webhookCancellationId,
            PaymentResult completedResult
    ) {
        public static ConfirmationClaim requiresLookup(
                String paymentId,
                String portOnePaymentId,
                long amountMinor,
                String currency
        ) {
            return new ConfirmationClaim(
                    true, paymentId, portOnePaymentId, amountMinor, currency,
                    null, null, null, null);
        }

        public static ConfirmationClaim requiresWebhookLookup(
                String paymentId,
                String portOnePaymentId,
                long amountMinor,
                String currency,
                String webhookMessageId,
                String webhookType,
                String webhookCancellationId
        ) {
            return new ConfirmationClaim(
                    true, paymentId, portOnePaymentId, amountMinor, currency,
                    webhookMessageId, webhookType, webhookCancellationId, null);
        }

        public static ConfirmationClaim completed(PaymentResult result) {
            return new ConfirmationClaim(
                    false, result.paymentId(), null, 0L, null,
                    null, null, null, result);
        }

        boolean isCancellationWebhook() {
            return webhookType != null && webhookType.startsWith("Transaction.")
                    && (webhookType.endsWith("Cancelled")
                    || webhookType.endsWith("CancelPending"));
        }
    }

    record RefundClaim(
            boolean requiresProviderCall,
            String refundId,
            String paymentId,
            String portOnePaymentId,
            long amountMinor,
            String currency,
            String reasonCode,
            RefundResult completedResult,
            PaymentErrorCode rejectionError
    ) {
        public static RefundClaim requiresCall(
                String refundId,
                String paymentId,
                String portOnePaymentId,
                long amountMinor,
                String currency,
                String reasonCode
        ) {
            return new RefundClaim(
                    true, refundId, paymentId, portOnePaymentId,
                    amountMinor, currency, reasonCode, null, null);
        }

        public static RefundClaim completed(RefundResult result) {
            return new RefundClaim(
                    false, result.refundId(), result.paymentId(), null,
                    result.requestedAmountMinor(), result.currency(), null, result, null);
        }

        public static RefundClaim rejected(PaymentErrorCode errorCode) {
            return new RefundClaim(
                    false, null, null, null, 0L, null, null, null, errorCode);
        }
    }

    record DispositionClaim(
            boolean requiresRefund,
            String dispositionId,
            RequestRefundCommand refundCommand,
            DispositionResult completedResult
    ) {
        public static DispositionClaim requiresRefund(
                String dispositionId,
                RequestRefundCommand refundCommand
        ) {
            return new DispositionClaim(true, dispositionId, refundCommand, null);
        }

        public static DispositionClaim completed(DispositionResult result) {
            return new DispositionClaim(false, result.dispositionId(), null, result);
        }
    }

    record DispositionReconciliationClaim(
            boolean requiresProviderLookup,
            String dispositionId,
            String paymentId,
            String portOnePaymentId,
            String refundId,
            long paymentAmountMinor,
            long refundAmountMinor,
            String currency,
            String reasonCode,
            DispositionResult completedResult
    ) {
        public static DispositionReconciliationClaim requiresLookup(
                String dispositionId,
                String paymentId,
                String portOnePaymentId,
                String refundId,
                long paymentAmountMinor,
                long refundAmountMinor,
                String currency,
                String reasonCode,
                DispositionResult currentResult
        ) {
            return new DispositionReconciliationClaim(
                    true, dispositionId, paymentId, portOnePaymentId, refundId,
                    paymentAmountMinor, refundAmountMinor, currency, reasonCode, currentResult);
        }

        public static DispositionReconciliationClaim completed(DispositionResult result) {
            return new DispositionReconciliationClaim(
                    false, result.dispositionId(), result.paymentId(), null, result.refundId(),
                    result.originalAmountMinor(), result.incrementalRefundAmountMinor(),
                    result.currency(), null, result);
        }
    }

    private static final String RESERVATION_DEPOSIT = "RESERVATION_DEPOSIT";
    private static final String WAITING_RESERVATION_DEPOSIT = "WAITING_RESERVATION_DEPOSIT";
    private static final Duration CONFIRMATION_PROCESSING_LEASE = Duration.ofMinutes(5);
    private static final Duration REFUND_PROCESSING_LEASE = Duration.ofMinutes(5);

    private enum DispositionAvailability {
        AVAILABLE,
        TEMPORARILY_BLOCKED,
        PERMANENTLY_BLOCKED
    }

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final PaymentRefundRepository refunds;
    private final ReservationDepositDispositionRepository dispositions;
    private final PaymentLedgerEntryRepository ledger;
    private final PaymentReferenceAllocator references;
    private final PaymentSettings settings;
    private final Clock clock;

    public PaymentTransactionService(
            PaymentRepository payments,
            PaymentAttemptRepository attempts,
            PaymentRefundRepository refunds,
            ReservationDepositDispositionRepository dispositions,
            PaymentLedgerEntryRepository ledger,
            PaymentReferenceAllocator references,
            PaymentSettings settings,
            Clock clock
    ) {
        this.payments = payments;
        this.attempts = attempts;
        this.refunds = refunds;
        this.dispositions = dispositions;
        this.ledger = ledger;
        this.references = references;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public PaymentPreparation prepare(PrepareReservationDepositCommand command, Instant now) {
        return prepare(
                RESERVATION_DEPOSIT,
                command.sourceReferenceId(),
                command.consumerAccountId(),
                command.amountMinor(),
                command.currency(),
                command.sourceExpiresAt(),
                command.sourcePolicyVersion(),
                command.idempotencyKey(),
                preparationFingerprint(command),
                now
        );
    }

    /** Prepares a deposit using the Payment-owned waiting-reservation source type. */
    @Transactional
    public PaymentPreparation prepareWaitingReservationDeposit(
            PrepareWaitingReservationDepositCommand command,
            Instant now
    ) {
        return prepare(
                WAITING_RESERVATION_DEPOSIT,
                command.sourceReferenceId(),
                command.consumerAccountId(),
                command.amountMinor(),
                command.currency(),
                command.sourceExpiresAt(),
                command.sourcePolicyVersion(),
                command.idempotencyKey(),
                preparationFingerprint(command),
                now
        );
    }

    private PaymentPreparation prepare(
            String sourceType,
            String sourceReferenceId,
            long consumerAccountId,
            long amountMinor,
            String currency,
            Instant sourceExpiresAt,
            long sourcePolicyVersion,
            String idempotencyKey,
            String fingerprint,
            Instant now
    ) {
        if (!sourceExpiresAt.isAfter(now)) {
            throw new ServiceException(PaymentErrorCode.SOURCE_EXPIRED);
        }
        Payment idempotent = payments.findBySourceTypeAndPreparationIdempotencyKey(
                sourceType,
                idempotencyKey
        ).orElse(null);
        if (idempotent != null) {
            if (idempotent.getPreparationRequestFingerprint().equals(fingerprint)) {
                return toPreparation(idempotent);
            }
            throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        Payment existing = payments.findBySourceTypeAndSourceReferenceId(
                sourceType,
                sourceReferenceId
        ).orElse(null);
        if (existing != null) {
            throw new ServiceException(PaymentErrorCode.ACTIVE_SOURCE_CONFLICT);
        }

        String paymentId = references.nextPaymentId();
        Payment payment = Payment.prepare(
                paymentId,
                sourceType,
                sourceReferenceId,
                sourcePolicyVersion,
                sourceExpiresAt,
                idempotencyKey,
                fingerprint,
                consumerAccountId,
                amountMinor,
                currency,
                "payment-reservation-" + paymentId,
                "MiriYum 예약금 " + sourceReferenceId,
                now
        );
        payments.saveAndFlush(payment);
        ledger.save(PaymentLedgerEntry.record(
                payment,
                null,
                "payment-prepared:" + paymentId,
                Type.PAYMENT_PREPARED,
                payment.getAmountMinor(),
                now
        ));
        return toPreparation(payment);
    }

    @Transactional(readOnly = true)
    public PaymentPreparation replayPreparation(PrepareReservationDepositCommand command) {
        return replayPreparation(
                RESERVATION_DEPOSIT,
                command.sourceReferenceId(),
                command.idempotencyKey(),
                preparationFingerprint(command)
        );
    }

    /** Replays the stored waiting-reservation preparation after a duplicate-key race. */
    @Transactional(readOnly = true)
    public PaymentPreparation replayWaitingReservationDeposit(
            PrepareWaitingReservationDepositCommand command
    ) {
        return replayPreparation(
                WAITING_RESERVATION_DEPOSIT,
                command.sourceReferenceId(),
                command.idempotencyKey(),
                preparationFingerprint(command)
        );
    }

    private PaymentPreparation replayPreparation(
            String sourceType,
            String sourceReferenceId,
            String idempotencyKey,
            String fingerprint
    ) {
        Payment idempotent = payments.findBySourceTypeAndPreparationIdempotencyKey(
                sourceType,
                idempotencyKey
        ).orElse(null);
        if (idempotent != null) {
            if (idempotent.getPreparationRequestFingerprint().equals(fingerprint)) {
                return toPreparation(idempotent);
            }
            throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (payments.findBySourceTypeAndSourceReferenceId(
                sourceType, sourceReferenceId).isPresent()) {
            throw new ServiceException(PaymentErrorCode.ACTIVE_SOURCE_CONFLICT);
        }
        throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfirmationClaim claimConfirmation(ConfirmPaymentCommand command, Instant now) {
        String fingerprint = confirmationFingerprint(command);
        PaymentAttempt idempotentAttempt = attempts.findByPrincipalIdAndIdempotencyKey(
                command.consumerAccountId(), command.idempotencyKey()).orElse(null);
        if (idempotentAttempt != null) {
            if (!idempotentAttempt.getRequestFingerprint().equals(fingerprint)) {
                throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            if (idempotentAttempt.getStatus() == Payment.AttemptStatus.PENDING) {
                Payment payment = ownedPaymentForUpdate(
                        command.paymentId(), command.consumerAccountId());
                if (payment.getStatus() != Payment.Status.CONFIRMING) {
                    return ConfirmationClaim.completed(toResult(payment));
                }
                if (confirmationLeaseExpired(idempotentAttempt, now)) {
                    return isolateStaleConfirmation(payment, idempotentAttempt, now);
                }
                throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
            }
            return ConfirmationClaim.completed(toResult(idempotentAttempt.getPayment()));
        }
        Payment payment = ownedPaymentForUpdate(command.paymentId(), command.consumerAccountId());
        if (!payment.getPortOnePaymentId().equals(command.portOnePaymentId())) {
            throw new ServiceException(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);
        }
        if (payment.getStatus() == Payment.Status.RECONCILIATION_REQUIRED) {
            return ConfirmationClaim.completed(toResult(payment));
        }
        if (payment.getStatus() == Payment.Status.PAID
                || payment.getStatus() == Payment.Status.PARTIALLY_REFUNDED
                || payment.getStatus() == Payment.Status.REFUNDED) {
            return ConfirmationClaim.completed(toResult(payment));
        }
        if (payment.getStatus() == Payment.Status.CONFIRMING) {
            PaymentAttempt pendingAttempt = latestAttempt(payment);
            if (pendingAttempt.getStatus() == Payment.AttemptStatus.PENDING
                    && confirmationLeaseExpired(pendingAttempt, now)) {
                return isolateStaleConfirmation(payment, pendingAttempt, now);
            }
            throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
        }
        if (!now.isBefore(payment.getSourceExpiresAt())) {
            throw new ServiceException(PaymentErrorCode.SOURCE_EXPIRED);
        }
        payment.beginConfirmation(now);
        int attemptNo = Math.toIntExact(attempts.countByPayment_Id(payment.getId()) + 1L);
        attempts.save(PaymentAttempt.start(
                payment,
                attemptNo,
                command.consumerAccountId(),
                command.idempotencyKey(),
                fingerprint,
                now
        ));
        return ConfirmationClaim.requiresLookup(
                payment.getPaymentId(),
                payment.getPortOnePaymentId(),
                payment.getAmountMinor(),
                payment.getCurrency()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfirmationClaim claimWebhookConfirmation(
            String portOnePaymentId,
            String webhookMessageId,
            String webhookType,
            String webhookCancellationId,
            Instant now
    ) {
        Payment payment = payments.findByPortOnePaymentIdForUpdate(portOnePaymentId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        boolean cancellationWebhook = webhookType.endsWith("Cancelled")
                || webhookType.endsWith("CancelPending");
        if (!cancellationWebhook && isRefundReconciliationRequired(payment)) {
            return ConfirmationClaim.completed(toResult(payment));
        }
        if (!cancellationWebhook && (payment.getStatus() == Payment.Status.READY
                || payment.getStatus() == Payment.Status.RECONCILIATION_REQUIRED)) {
            payment.beginConfirmation(now);
            int attemptNo = Math.toIntExact(attempts.countByPayment_Id(payment.getId()) + 1L);
            String idempotencyKey = java.util.UUID.nameUUIDFromBytes(
                    ("portone-webhook:" + webhookMessageId).getBytes(StandardCharsets.UTF_8)
            ).toString();
            attempts.save(PaymentAttempt.start(
                    payment,
                    attemptNo,
                    payment.getConsumerAccountId(),
                    idempotencyKey,
                    sha256(payment.getPaymentId() + "\n" + portOnePaymentId),
                    now
            ));
        }
        return ConfirmationClaim.requiresWebhookLookup(
                payment.getPaymentId(),
                payment.getPortOnePaymentId(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                webhookMessageId,
                webhookType,
                webhookCancellationId
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentResult finalizeConfirmation(
            ConfirmationClaim claim,
            ProviderPayment providerPayment,
            Instant now
    ) {
        Payment payment = paymentForUpdate(claim.paymentId());
        requireProviderMapping(claim, providerPayment);
        if (claim.isCancellationWebhook()) {
            isolateUnmatchedWebhookCancellation(payment, claim, now);
            return toResult(payment);
        }
        if (payment.getStatus() != Payment.Status.CONFIRMING) {
            return toResult(payment);
        }
        PaymentAttempt attempt = latestAttempt(payment);
        switch (providerPayment.status()) {
            case PAID -> {
                payment.markPaid(providerPayment.transactionId(), now);
                attempt.finish(Payment.AttemptStatus.PAID, providerPayment.transactionId(), now);
                ledger.save(PaymentLedgerEntry.record(
                        payment,
                        null,
                        "payment-confirmed:" + providerPayment.transactionId(),
                        Type.PAYMENT_CONFIRMED,
                        payment.getAmountMinor(),
                        now
                ));
            }
            case FAILED -> {
                payment.markFailed(now);
                attempt.finish(Payment.AttemptStatus.FAILED, providerPayment.transactionId(), now);
                ledger.save(PaymentLedgerEntry.record(
                        payment, null, "payment-failed:" + payment.getPaymentId()
                                + ":" + attempt.getAttemptNo(),
                        Type.PAYMENT_FAILED, 0L, now));
            }
            case CANCELLED -> {
                payment.markCancelled(now);
                attempt.finish(Payment.AttemptStatus.CANCELLED, providerPayment.transactionId(), now);
            }
            case PAY_PENDING, PARTIALLY_CANCELLED, UNKNOWN -> {
                payment.markReconciliationRequired(now);
                attempt.finish(Payment.AttemptStatus.UNKNOWN, providerPayment.transactionId(), now);
                recordPaymentReconciliation(payment, attempt, now);
            }
        }
        return toResult(payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentResult markConfirmationUnknown(ConfirmationClaim claim, Instant now) {
        Payment payment = paymentForUpdate(claim.paymentId());
        if (claim.isCancellationWebhook()) {
            isolateUnmatchedWebhookCancellation(payment, claim, now);
        } else if (payment.getStatus() == Payment.Status.CONFIRMING) {
            PaymentAttempt attempt = latestAttempt(payment);
            payment.markReconciliationRequired(now);
            attempt.finish(Payment.AttemptStatus.UNKNOWN, null, now);
            recordPaymentReconciliation(payment, attempt, now);
        }
        return toResult(payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentResult markConfirmationMismatch(ConfirmationClaim claim, Instant now) {
        Payment payment = paymentForUpdate(claim.paymentId());
        if (claim.isCancellationWebhook()) {
            isolateUnmatchedWebhookCancellation(payment, claim, now);
        } else if (payment.getStatus() == Payment.Status.CONFIRMING) {
            PaymentAttempt attempt = latestAttempt(payment);
            payment.markReconciliationRequired(now);
            attempt.finish(Payment.AttemptStatus.UNKNOWN, null, now);
            recordPaymentReconciliation(payment, attempt, now);
        }
        return toResult(payment);
    }

    private void isolateUnmatchedWebhookCancellation(
            Payment payment,
            ConfirmationClaim claim,
            Instant now
    ) {
        boolean knownCancellation = refunds.existsByPayment_IdAndProviderCancellationIdAndStatus(
                payment.getId(),
                claim.webhookCancellationId(),
                RefundStatus.COMPLETED
        );
        if (knownCancellation) {
            return;
        }
        if (payment.getStatus() == Payment.Status.CONFIRMING) {
            PaymentAttempt attempt = latestAttempt(payment);
            attempt.finish(Payment.AttemptStatus.UNKNOWN, null, now);
        }
        payment.markRefundReconciliationRequired(now);
        ledger.save(PaymentLedgerEntry.record(
                payment,
                null,
                "payment-webhook-reconciliation:" + claim.webhookMessageId(),
                Type.PAYMENT_RECONCILIATION_REQUIRED,
                0L,
                now
        ));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundClaim claimRefund(RequestRefundCommand command, Instant now) {
        Payment payment = paymentForUpdate(command.paymentId());
        String fingerprint = refundFingerprint(command);
        PaymentRefund existing = refunds.findByPayment_IdAndIdempotencyKey(
                payment.getId(), command.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
        }
        List<PaymentRefund> expiredProcessing =
                refunds
                .findByPayment_IdAndStatusAndProcessingStartedAtLessThanEqualOrderByProcessingStartedAtAsc(
                        payment.getId(),
                        RefundStatus.PROCESSING,
                        now.minus(REFUND_PROCESSING_LEASE)
                );
        expiredProcessing.forEach(refund -> markRefundReconciliation(refund, payment, now));
        if (existing != null && existing.getStatus() == RefundStatus.FAILED) {
            if (payment.getStatus() != Payment.Status.PAID
                    && payment.getStatus() != Payment.Status.PARTIALLY_REFUNDED) {
                throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
            }
            long processingAmount = refunds.sumAmountMinorByPaymentIdAndStatus(
                    payment.getId(), RefundStatus.PROCESSING);
            long availableAmount = payment.getRefundableAmountMinor() - processingAmount;
            if (existing.getAmountMinor() > availableAmount) {
                throw new ServiceException(PaymentErrorCode.REFUND_AMOUNT_EXCEEDED);
            }
            existing.retry(now);
            ledger.save(PaymentLedgerEntry.record(
                    payment,
                    existing,
                    "refund-retry-requested:" + existing.getRefundId()
                            + ":" + existing.getAttemptCount(),
                    Type.REFUND_RETRY_REQUESTED,
                    existing.getAmountMinor(),
                    now
            ));
            return RefundClaim.requiresCall(
                    existing.getRefundId(),
                    payment.getPaymentId(),
                    payment.getPortOnePaymentId(),
                    existing.getAmountMinor(),
                    existing.getCurrency(),
                    existing.getReasonCode()
            );
        }
        if (existing != null) {
            return RefundClaim.completed(toRefundResult(existing));
        }
        if (!expiredProcessing.isEmpty()) {
            return RefundClaim.rejected(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        if (refunds.findByPayment_IdAndSourceEventId(
                payment.getId(), command.sourceEventId()).isPresent()) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        if (payment.getStatus() != Payment.Status.PAID
                && payment.getStatus() != Payment.Status.PARTIALLY_REFUNDED) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        long processingAmount = refunds.sumAmountMinorByPaymentIdAndStatus(
                payment.getId(), RefundStatus.PROCESSING);
        long availableAmount = payment.getRefundableAmountMinor() - processingAmount;
        if (command.refundAmountMinor() > availableAmount) {
            throw new ServiceException(PaymentErrorCode.REFUND_AMOUNT_EXCEEDED);
        }
        String refundId = references.nextRefundId();
        PaymentRefund refund = PaymentRefund.request(
                refundId,
                payment,
                command.idempotencyKey(),
                fingerprint,
                command.sourceEventId(),
                command.refundAmountMinor(),
                command.reasonCode(),
                command.policyVersion(),
                now
        );
        refunds.saveAndFlush(refund);
        ledger.save(PaymentLedgerEntry.record(
                payment,
                refund,
                "refund-requested:" + refundId,
                Type.REFUND_REQUESTED,
                refund.getAmountMinor(),
                now
        ));
        return RefundClaim.requiresCall(
                refundId,
                payment.getPaymentId(),
                payment.getPortOnePaymentId(),
                refund.getAmountMinor(),
                refund.getCurrency(),
                refund.getReasonCode()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundResult finalizeRefund(
            RefundClaim claim,
            ProviderCancellation cancellation,
            Instant now
    ) {
        Payment payment = paymentForUpdate(claim.paymentId());
        PaymentRefund refund = refundForUpdate(claim.refundId());
        if (!refund.getPayment().getId().equals(payment.getId())) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        if (refund.getStatus() == RefundStatus.COMPLETED) {
            return toRefundResult(refund);
        }
        if (cancellation.amountMinor() != refund.getAmountMinor()
                || !cancellation.currency().equals(refund.getCurrency())) {
            throw new ServiceException(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);
        }
        switch (cancellation.status()) {
            case CANCELLED, PARTIALLY_CANCELLED -> {
                payment.applyCompletedRefund(refund.getAmountMinor(), now);
                refund.complete(cancellation.cancellationId(), now);
                if (shouldKeepPaymentReconciliationAfterRefundCompletion(payment, refund)) {
                    payment.markRefundReconciliationRequired(now);
                }
                ledger.save(PaymentLedgerEntry.record(
                        payment,
                        refund,
                        "refund-completed:" + cancellation.cancellationId(),
                        Type.REFUND_COMPLETED,
                        refund.getAmountMinor(),
                        now
                ));
            }
            case FAILED -> {
                refund.fail(now);
                if (payment.getStatus() == Payment.Status.RECONCILIATION_REQUIRED
                        && canRestorePaymentAfterRefundFailure(payment, refund)) {
                    payment.restoreRefundableStatusAfterReconciliation(now);
                }
                ledger.save(PaymentLedgerEntry.record(
                        payment,
                        refund,
                        "refund-failed:" + refund.getRefundId()
                                + ":" + refund.getAttemptCount(),
                        Type.REFUND_FAILED,
                        refund.getAmountMinor(),
                        now
                ));
            }
            case PAY_PENDING, PAID, UNKNOWN -> markRefundReconciliation(refund, payment, now);
        }
        return toRefundResult(refund);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundResult markRefundUnknown(RefundClaim claim, Instant now) {
        Payment payment = paymentForUpdate(claim.paymentId());
        PaymentRefund refund = refundForUpdate(claim.refundId());
        if (!refund.getPayment().getId().equals(payment.getId())) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        if (refund.getStatus() != RefundStatus.COMPLETED) {
            markRefundReconciliation(refund, payment, now);
        }
        return toRefundResult(refund);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundResult markRefundMismatch(RefundClaim claim, Instant now) {
        Payment payment = paymentForUpdate(claim.paymentId());
        PaymentRefund refund = refundForUpdate(claim.refundId());
        if (!refund.getPayment().getId().equals(payment.getId())) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        if (refund.getStatus() != RefundStatus.COMPLETED) {
            markRefundReconciliation(refund, payment, now);
        }
        return toRefundResult(refund);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DispositionClaim claimDisposition(
            ApplyReservationDepositDispositionCommand command,
            Instant now
    ) {
        Payment payment = paymentForUpdate(command.paymentId());
        String fingerprint = dispositionFingerprint(command);
        ReservationDepositDisposition existing = dispositions
                .findByPayment_IdAndIdempotencyKey(payment.getId(), command.idempotencyKey())
                .orElse(null);
        if (existing != null) {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            if (existing.getStatus() == DispositionStatus.FAILED
                    && existing.getFailureClassification()
                    == DispositionFailureClassification.RETRYABLE) {
                PaymentRefund matchingRefund = refunds.findByPayment_IdAndIdempotencyKey(
                        payment.getId(), existing.getIdempotencyKey()).orElse(null);
                if (matchingRefund != null) {
                    matchingRefund = refundForUpdate(matchingRefund.getRefundId());
                    if (!matchesDispositionRefund(existing, matchingRefund)) {
                        existing.rejectRetryPermanently(now);
                        return DispositionClaim.completed(toDispositionResult(existing));
                    }
                    switch (matchingRefund.getStatus()) {
                        case COMPLETED -> {
                            existing.completeRetryableRefund(
                                    matchingRefund.getRefundId(),
                                    matchingRefund.getAmountMinor(),
                                    now);
                            return DispositionClaim.completed(toDispositionResult(existing));
                        }
                        case RECONCILIATION_REQUIRED -> {
                            existing.requireReconciliationFromRetryable(
                                    matchingRefund.getRefundId(), now);
                            return DispositionClaim.completed(toDispositionResult(existing));
                        }
                        case REQUESTED, VALIDATING, PROCESSING -> {
                            existing.resumeRetryableRefund(
                                    matchingRefund.getRefundId(), now);
                            return DispositionClaim.completed(toDispositionResult(existing));
                        }
                        case FAILED -> existing.attachRetryableRefund(
                                matchingRefund.getRefundId(), now);
                    }
                }
                DispositionAvailability availability = dispositionAvailability(
                        payment, existing.getIncrementalRefundAmountMinor());
                if (availability == DispositionAvailability.PERMANENTLY_BLOCKED) {
                    existing.rejectRetryPermanently(now);
                    return DispositionClaim.completed(toDispositionResult(existing));
                }
                if (availability == DispositionAvailability.TEMPORARILY_BLOCKED) {
                    return DispositionClaim.completed(toDispositionResult(existing));
                }
                existing.retry(now);
                return refundClaimFor(existing);
            }
            if (existing.getStatus() == DispositionStatus.PROCESSING) {
                return refundClaimFor(existing);
            }
            return DispositionClaim.completed(toDispositionResult(existing));
        }
        ReservationDepositDisposition existingSource = dispositions
                .findByPayment_IdAndSourceEventId(
                        payment.getId(), command.sourceEventId())
                .orElse(null);
        if (existingSource != null) {
            if (!existingSource.getRequestFingerprint().equals(fingerprint)) {
                throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
            }
            return DispositionClaim.completed(toDispositionResult(existingSource));
        }

        long previouslyCompleted = 0L;
        boolean invalidCorrection = false;
        if (command.correctsSourceEventId() != null) {
            ReservationDepositDisposition corrected = dispositions
                    .findByPayment_IdAndSourceEventId(
                            payment.getId(), command.correctsSourceEventId())
                    .orElse(null);
            if (corrected == null) {
                invalidCorrection = true;
            } else {
                previouslyCompleted = corrected.getCompletedRefundAmountMinor();
                invalidCorrection = corrected.getStatus() != DispositionStatus.COMPLETED
                        || dispositions.existsLineageChild(
                                payment.getId(),
                                command.correctsSourceEventId(),
                                DispositionStatus.FAILED,
                                DispositionFailureClassification.PERMANENT);
            }
        }

        long targetAmount = dispositionTargetAmount(
                payment.getAmountMinor(), command.targetRefundRateBasisPoints());
        long incrementalAmount = Math.max(0L, targetAmount - previouslyCompleted);
        boolean invalid = !RESERVATION_DEPOSIT.equals(payment.getSourceType())
                || invalidCorrection
                || targetAmount < previouslyCompleted
                || incrementalAmount > payment.getRefundableAmountMinor();
        DispositionAvailability availability = invalid
                ? DispositionAvailability.PERMANENTLY_BLOCKED
                : dispositionAvailability(payment, incrementalAmount);
        ReservationDepositDisposition disposition = switch (availability) {
            case PERMANENTLY_BLOCKED -> ReservationDepositDisposition.rejectPermanent(
                        payment,
                        command.idempotencyKey(),
                        command.sourceEventId(),
                        command.sourceEventType(),
                        command.correctsSourceEventId(),
                        command.policyVersion(),
                        command.responsibilityCode(),
                        command.targetRefundRateBasisPoints(),
                        targetAmount,
                        previouslyCompleted,
                        fingerprint,
                        now);
            case TEMPORARILY_BLOCKED -> ReservationDepositDisposition.deferRetryable(
                        payment,
                        command.idempotencyKey(),
                        command.sourceEventId(),
                        command.sourceEventType(),
                        command.correctsSourceEventId(),
                        command.policyVersion(),
                        command.responsibilityCode(),
                        command.targetRefundRateBasisPoints(),
                        targetAmount,
                        previouslyCompleted,
                        fingerprint,
                        now);
            case AVAILABLE -> ReservationDepositDisposition.create(
                        payment,
                        command.idempotencyKey(),
                        command.sourceEventId(),
                        command.sourceEventType(),
                        command.correctsSourceEventId(),
                        command.policyVersion(),
                        command.responsibilityCode(),
                        command.targetRefundRateBasisPoints(),
                        targetAmount,
                        previouslyCompleted,
                        fingerprint,
                        now);
        };
        dispositions.saveAndFlush(disposition);
        if (disposition.getStatus() != DispositionStatus.PROCESSING) {
            return DispositionClaim.completed(toDispositionResult(disposition));
        }
        return refundClaimFor(disposition);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DispositionResult finalizeDisposition(
            String dispositionId,
            RefundResult refundResult,
            Instant now
    ) {
        ReservationDepositDisposition disposition = dispositionForUpdate(dispositionId);
        if (disposition.getStatus() == DispositionStatus.FAILED
                && disposition.getFailureClassification()
                == DispositionFailureClassification.RETRYABLE) {
            if (refundResult.status() == RefundStatus.COMPLETED
                    && matchesDispositionRefundResult(disposition, refundResult)) {
                disposition.completeRetryableRefund(
                        refundResult.refundId(), refundResult.completedAmountMinor(), now);
            }
            return toDispositionResult(disposition);
        }
        if (disposition.getStatus() != DispositionStatus.PROCESSING) {
            return toDispositionResult(disposition);
        }
        switch (refundResult.status()) {
            case COMPLETED -> disposition.completeRefund(
                    refundResult.refundId(), refundResult.completedAmountMinor(), now);
            case FAILED -> disposition.failRetryable(refundResult.refundId(), now);
            case RECONCILIATION_REQUIRED -> disposition.requireReconciliation(
                    refundResult.refundId(), now);
            case REQUESTED, VALIDATING, PROCESSING -> disposition.attachRefund(
                    refundResult.refundId(), now);
        }
        return toDispositionResult(disposition);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DispositionResult resolveDispositionAfterRefundFailure(
            String dispositionId,
            boolean retryableWithoutRefund,
            Instant now
    ) {
        ReservationDepositDisposition disposition = dispositionForUpdate(dispositionId);
        if (disposition.getStatus() != DispositionStatus.PROCESSING) {
            return toDispositionResult(disposition);
        }
        PaymentRefund refund = refunds.findByPayment_IdAndIdempotencyKey(
                disposition.getPayment().getId(), disposition.getIdempotencyKey()).orElse(null);
        if (refund == null || !matchesDispositionRefund(disposition, refund)) {
            if (refund == null && retryableWithoutRefund) {
                disposition.failRetryableWithoutRefund(now);
            } else {
                disposition.failPermanent(now);
            }
            return toDispositionResult(disposition);
        }
        switch (refund.getStatus()) {
            case COMPLETED -> disposition.completeRefund(
                    refund.getRefundId(), refund.getAmountMinor(), now);
            case FAILED -> disposition.failPermanent(now);
            case RECONCILIATION_REQUIRED -> disposition.requireReconciliation(
                    refund.getRefundId(), now);
            case REQUESTED, VALIDATING, PROCESSING -> disposition.attachRefund(
                    refund.getRefundId(), now);
        }
        return toDispositionResult(disposition);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DispositionReconciliationClaim claimDispositionReconciliation(
            GetReservationDepositDispositionQuery query,
            Instant now
    ) {
        Payment payment = paymentForUpdate(query.paymentId());
        ReservationDepositDisposition found = dispositions
                .findByPayment_PaymentIdAndSourceEventId(
                        query.paymentId(), query.sourceEventId())
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        ReservationDepositDisposition disposition = dispositionForUpdate(found.getDispositionId());
        boolean retryableFailure = disposition.getStatus() == DispositionStatus.FAILED
                && disposition.getFailureClassification()
                == DispositionFailureClassification.RETRYABLE;
        if ((disposition.getStatus() == DispositionStatus.PROCESSING
                || disposition.getStatus() == DispositionStatus.RECONCILIATION_REQUIRED
                || retryableFailure)
                && disposition.getRefundId() != null) {
            PaymentRefund processingRefund = refundForUpdate(disposition.getRefundId());
            if (processingRefund.getPayment().getId().equals(payment.getId())
                    && matchesDispositionRefund(disposition, processingRefund)) {
                switch (processingRefund.getStatus()) {
                    case COMPLETED -> {
                        if (disposition.getStatus() == DispositionStatus.PROCESSING) {
                            disposition.completeRefund(
                                    processingRefund.getRefundId(),
                                    processingRefund.getAmountMinor(),
                                    now);
                        } else if (disposition.getStatus()
                                == DispositionStatus.RECONCILIATION_REQUIRED) {
                            disposition.completeReconciledRefund(
                                    processingRefund.getRefundId(),
                                    processingRefund.getAmountMinor(),
                                    now);
                        } else {
                            disposition.completeRetryableRefund(
                                    processingRefund.getRefundId(),
                                    processingRefund.getAmountMinor(),
                                    now);
                        }
                    }
                    case FAILED -> {
                        if (disposition.getStatus() == DispositionStatus.PROCESSING) {
                            disposition.failRetryable(processingRefund.getRefundId(), now);
                        } else if (disposition.getStatus()
                                == DispositionStatus.RECONCILIATION_REQUIRED) {
                            disposition.failReconciledRetryable(
                                    processingRefund.getRefundId(), now);
                        }
                    }
                    case RECONCILIATION_REQUIRED -> {
                        if (disposition.getStatus() == DispositionStatus.PROCESSING) {
                            disposition.requireReconciliation(
                                    processingRefund.getRefundId(), now);
                        }
                    }
                    case PROCESSING -> {
                        if (!retryableFailure
                                && !now.isBefore(processingRefund.getProcessingStartedAt()
                                .plus(REFUND_PROCESSING_LEASE))) {
                            markRefundReconciliation(processingRefund, payment, now);
                            if (disposition.getStatus() == DispositionStatus.PROCESSING) {
                                disposition.requireReconciliation(
                                        processingRefund.getRefundId(), now);
                            }
                        }
                    }
                    case REQUESTED, VALIDATING -> {
                        // 저장 결과를 유지하고 아직 끝나지 않은 내부 단계는 다음 조회를 기다린다.
                    }
                }
            }
        }
        DispositionResult current = toDispositionResult(disposition);
        if (disposition.getStatus() != DispositionStatus.RECONCILIATION_REQUIRED
                || disposition.getFailureClassification()
                != DispositionFailureClassification.UNKNOWN
                || disposition.getRefundId() == null) {
            return DispositionReconciliationClaim.completed(current);
        }
        PaymentRefund refund = refundForUpdate(disposition.getRefundId());
        if (!refund.getPayment().getId().equals(payment.getId())
                || refund.getStatus() != RefundStatus.RECONCILIATION_REQUIRED
                || !matchesDispositionRefund(disposition, refund)) {
            return DispositionReconciliationClaim.completed(current);
        }
        return DispositionReconciliationClaim.requiresLookup(
                disposition.getDispositionId(),
                payment.getPaymentId(),
                payment.getPortOnePaymentId(),
                refund.getRefundId(),
                payment.getAmountMinor(),
                refund.getAmountMinor(),
                refund.getCurrency(),
                refund.getReasonCode(),
                current);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DispositionResult finalizeDispositionReconciliation(
            DispositionReconciliationClaim claim,
            ProviderCancellation cancellation,
            Instant now
    ) {
        Payment payment = paymentForUpdate(claim.paymentId());
        ReservationDepositDisposition disposition = dispositionForUpdate(claim.dispositionId());
        if (disposition.getStatus() != DispositionStatus.RECONCILIATION_REQUIRED) {
            return toDispositionResult(disposition);
        }
        PaymentRefund refund = refundForUpdate(claim.refundId());
        String expectedReason = PaymentProviderClient.cancellationReason(
                refund.getReasonCode(), refund.getRefundId());
        if (refund.getStatus() != RefundStatus.RECONCILIATION_REQUIRED
                || !refund.getPayment().getId().equals(payment.getId())
                || !disposition.getRefundId().equals(refund.getRefundId())
                || !matchesDispositionRefund(disposition, refund)
                || cancellation.amountMinor() != refund.getAmountMinor()
                || !cancellation.currency().equals(refund.getCurrency())
                || !expectedReason.equals(cancellation.reason())) {
            return toDispositionResult(disposition);
        }
        switch (cancellation.status()) {
            case CANCELLED, PARTIALLY_CANCELLED -> {
                payment.applyCompletedRefund(refund.getAmountMinor(), now);
                refund.complete(cancellation.cancellationId(), now);
                disposition.completeReconciledRefund(
                        refund.getRefundId(), refund.getAmountMinor(), now);
                ledger.save(PaymentLedgerEntry.record(
                        payment,
                        refund,
                        "refund-completed:" + cancellation.cancellationId(),
                        Type.REFUND_COMPLETED,
                        refund.getAmountMinor(),
                        now
                ));
                if (shouldKeepPaymentReconciliationAfterRefundCompletion(payment, refund)) {
                    payment.markRefundReconciliationRequired(now);
                }
            }
            case FAILED -> {
                refund.fail(now);
                disposition.failReconciledRetryable(refund.getRefundId(), now);
                if (payment.getStatus() == Payment.Status.RECONCILIATION_REQUIRED
                        && canRestorePaymentAfterRefundFailure(payment, refund)) {
                    payment.restoreRefundableStatusAfterReconciliation(now);
                }
                ledger.save(PaymentLedgerEntry.record(
                        payment,
                        refund,
                        "refund-failed:" + refund.getRefundId()
                                + ":" + refund.getAttemptCount(),
                        Type.REFUND_FAILED,
                        refund.getAmountMinor(),
                        now
                ));
            }
            case PAY_PENDING, PAID, UNKNOWN -> {
                return toDispositionResult(disposition);
            }
        }
        return toDispositionResult(disposition);
    }

    private DispositionClaim refundClaimFor(ReservationDepositDisposition disposition) {
        return DispositionClaim.requiresRefund(
                disposition.getDispositionId(),
                new RequestRefundCommand(
                        disposition.getPayment().getPaymentId(),
                        disposition.getSourceEventId(),
                        disposition.getIncrementalRefundAmountMinor(),
                        "RESERVATION_DEPOSIT_DISPOSITION",
                        disposition.getPolicyVersion(),
                        disposition.getIdempotencyKey()
                )
        );
    }

    private boolean matchesDispositionRefund(
            ReservationDepositDisposition disposition,
            PaymentRefund refund
    ) {
        return refund.getSourceEventId().equals(disposition.getSourceEventId())
                && refund.getAmountMinor() == disposition.getIncrementalRefundAmountMinor()
                && refund.getPolicyVersion() == disposition.getPolicyVersion()
                && "RESERVATION_DEPOSIT_DISPOSITION".equals(refund.getReasonCode());
    }

    private boolean matchesDispositionRefundResult(
            ReservationDepositDisposition disposition,
            RefundResult refund
    ) {
        return disposition.getRefundId() != null
                && disposition.getRefundId().equals(refund.refundId())
                && disposition.getPayment().getPaymentId().equals(refund.paymentId())
                && disposition.getIncrementalRefundAmountMinor() == refund.requestedAmountMinor()
                && disposition.getIncrementalRefundAmountMinor() == refund.completedAmountMinor();
    }

    private DispositionAvailability dispositionAvailability(
            Payment payment,
            long incrementalRefundAmountMinor
    ) {
        if (incrementalRefundAmountMinor > payment.getRefundableAmountMinor()) {
            return DispositionAvailability.PERMANENTLY_BLOCKED;
        }
        if (incrementalRefundAmountMinor == 0L) {
            return payment.getStatus() == Payment.Status.PAID
                    || payment.getStatus() == Payment.Status.PARTIALLY_REFUNDED
                    ? DispositionAvailability.AVAILABLE
                    : DispositionAvailability.PERMANENTLY_BLOCKED;
        }
        if (payment.getStatus() == Payment.Status.RECONCILIATION_REQUIRED
                && hasUnresolvedRefund(payment)) {
            return DispositionAvailability.TEMPORARILY_BLOCKED;
        }
        if (payment.getStatus() != Payment.Status.PAID
                && payment.getStatus() != Payment.Status.PARTIALLY_REFUNDED) {
            return DispositionAvailability.PERMANENTLY_BLOCKED;
        }
        long processingAmount = refunds.sumAmountMinorByPaymentIdAndStatus(
                payment.getId(), RefundStatus.PROCESSING);
        long availableAmount = Math.max(
                0L, payment.getRefundableAmountMinor() - processingAmount);
        return incrementalRefundAmountMinor <= availableAmount
                ? DispositionAvailability.AVAILABLE
                : DispositionAvailability.TEMPORARILY_BLOCKED;
    }

    private void markRefundReconciliation(PaymentRefund refund, Payment payment, Instant now) {
        refund.requireReconciliation(now);
        payment.markRefundReconciliationRequired(now);
        ledger.save(PaymentLedgerEntry.record(
                payment,
                refund,
                "refund-reconciliation:" + refund.getRefundId(),
                Type.REFUND_RECONCILIATION_REQUIRED,
                refund.getAmountMinor(),
                now
        ));
    }

    @Transactional(readOnly = true)
    public PaymentResult getOwnedPayment(String paymentId, long consumerAccountId) {
        Payment payment = payments.findByPaymentIdAndConsumerAccountId(paymentId, consumerAccountId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        return toResult(payment);
    }

    @Transactional(readOnly = true)
    public Optional<StoreReservationPaymentSnapshot> findReservationDepositPayment(
            String paymentId,
            String sourceReferenceId
    ) {
        return payments.findByPaymentIdAndSourceTypeAndSourceReferenceId(
                paymentId, RESERVATION_DEPOSIT, sourceReferenceId)
                .map(this::toStoreReservationPaymentSnapshot);
    }

    @Transactional
    public VerifiedWaitingReservationDeposit getVerifiedWaitingReservationDeposit(
            String paymentId,
            long waitingTeamId,
            long consumerAccountId
    ) {
        Payment payment = paymentForUpdate(paymentId);
        requireVerifiedWaitingReservationDeposit(
                payment, paymentId, waitingTeamId, consumerAccountId);
        return toVerifiedWaitingReservationDeposit(payment);
    }

    @Transactional
    public VerifiedWaitingReservationDeposit getCompletableWaitingReservationDeposit(
            String paymentId,
            long waitingTeamId,
            long consumerAccountId
    ) {
        Payment payment = paymentForUpdate(paymentId);
        boolean hasAnyRefund = !refunds.findByPayment_IdOrderByRequestedAtAsc(payment.getId())
                .isEmpty();
        requireCompletableWaitingReservationDeposit(
                payment, paymentId, waitingTeamId, consumerAccountId, hasAnyRefund);
        return toVerifiedWaitingReservationDeposit(payment);
    }

    static Payment requireVerifiedWaitingReservationDeposit(
            Payment payment,
            String paymentId,
            long waitingTeamId,
            long consumerAccountId
    ) {
        boolean verified = payment != null
                && payment.getPaymentId().equals(paymentId)
                && payment.getConsumerAccountId().equals(consumerAccountId)
                && "WAITING_RESERVATION_DEPOSIT".equals(payment.getSourceType())
                && Long.toString(waitingTeamId).equals(payment.getSourceReferenceId())
                && payment.getPaidAt() != null
                && (payment.getStatus() == Payment.Status.PAID
                    || payment.getStatus() == Payment.Status.PARTIALLY_REFUNDED
                    || payment.getStatus() == Payment.Status.REFUNDED
                    || payment.getStatus() == Payment.Status.RECONCILIATION_REQUIRED);
        if (!verified) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND);
        }
        return payment;
    }

    static Payment requireCompletableWaitingReservationDeposit(
            Payment payment,
            String paymentId,
            long waitingTeamId,
            long consumerAccountId,
            boolean hasAnyRefund
    ) {
        requireVerifiedWaitingReservationDeposit(
                payment, paymentId, waitingTeamId, consumerAccountId);
        if (payment.getStatus() != Payment.Status.PAID || hasAnyRefund) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        return payment;
    }

    @Transactional(readOnly = true)
    public PaymentHistorySlice getConsumerPaymentHistory(PaymentHistoryQuery query) {
        String cursorSecret = settings.requireCursorSecret();
        String fingerprint = historyFingerprint(cursorSecret, query);
        PaymentCursorCodec.Cursor cursor = query.cursor() == null
                ? null
                : new PaymentCursorCodec(cursorSecret, clock)
                        .decode(query.cursor(), fingerprint);
        List<Payment> found = payments.findHistory(
                query.consumerAccountId(),
                query.status() == null ? null : Payment.Status.valueOf(query.status().name()),
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.paymentId(),
                PageRequest.of(0, query.size() + 1)
        );
        boolean hasNext = found.size() > query.size();
        List<Payment> page = hasNext ? found.subList(0, query.size()) : found;
        String nextCursor = null;
        if (hasNext && !page.isEmpty()) {
            Payment last = page.get(page.size() - 1);
            nextCursor = new PaymentCursorCodec(cursorSecret, clock)
                    .encode(last.getCreatedAt(), last.getPaymentId(), fingerprint);
        }
        return new PaymentHistorySlice(page.stream().map(this::toResult).toList(), nextCursor, hasNext);
    }

    private Payment ownedPaymentForUpdate(String paymentId, long ownerId) {
        Payment payment = paymentForUpdate(paymentId);
        if (!payment.getConsumerAccountId().equals(ownerId)) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND);
        }
        return payment;
    }

    private Payment paymentForUpdate(String paymentId) {
        return payments.findByPaymentIdForUpdate(paymentId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    private PaymentRefund refundForUpdate(String refundId) {
        return refunds.findByRefundIdForUpdate(refundId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    private ReservationDepositDisposition dispositionForUpdate(String dispositionId) {
        return dispositions.findByDispositionIdForUpdate(dispositionId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    private PaymentAttempt latestAttempt(Payment payment) {
        return attempts.findFirstByPayment_IdOrderByAttemptNoDesc(payment.getId())
                .orElseThrow(() -> new IllegalStateException("confirmation attempt is missing"));
    }

    private boolean confirmationLeaseExpired(PaymentAttempt attempt, Instant now) {
        return !now.isBefore(attempt.getStartedAt().plus(CONFIRMATION_PROCESSING_LEASE));
    }

    private ConfirmationClaim isolateStaleConfirmation(
            Payment payment,
            PaymentAttempt attempt,
            Instant now
    ) {
        payment.markReconciliationRequired(now);
        attempt.finish(Payment.AttemptStatus.UNKNOWN, null, now);
        recordPaymentReconciliation(payment, attempt, now);
        return ConfirmationClaim.completed(toResult(payment));
    }

    private boolean hasUnresolvedRefund(Payment payment) {
        return refunds.existsByPayment_IdAndStatusIn(
                payment.getId(),
                List.of(RefundStatus.PROCESSING, RefundStatus.RECONCILIATION_REQUIRED)
        );
    }

    private boolean canRestorePaymentAfterRefundFailure(
            Payment payment,
            PaymentRefund refund
    ) {
        return !hasUnresolvedRefund(payment)
                && !hasIndependentPaymentReconciliationSince(payment, refund);
    }

    private boolean shouldKeepPaymentReconciliationAfterRefundCompletion(
            Payment payment,
            PaymentRefund refund
    ) {
        return hasUnresolvedRefund(payment)
                || hasIndependentPaymentReconciliationSince(payment, refund);
    }

    private boolean hasIndependentPaymentReconciliationSince(
            Payment payment,
            PaymentRefund refund
    ) {
        return refunds.countPaymentLedgerEntriesAtOrAfter(
                payment.getId(),
                Type.PAYMENT_RECONCILIATION_REQUIRED,
                refund.getProcessingStartedAt()
        ) > 0L;
    }

    private boolean isRefundReconciliationRequired(Payment payment) {
        return payment.getStatus() == Payment.Status.RECONCILIATION_REQUIRED
                && hasUnresolvedRefund(payment);
    }

    private void requireProviderMapping(ConfirmationClaim claim, ProviderPayment providerPayment) {
        if (!claim.portOnePaymentId().equals(providerPayment.portOnePaymentId())
                || claim.amountMinor() != providerPayment.amountMinor()
                || !claim.currency().equals(providerPayment.currency())) {
            throw new ServiceException(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);
        }
    }

    private void recordPaymentReconciliation(
            Payment payment,
            PaymentAttempt attempt,
            Instant now
    ) {
        ledger.save(PaymentLedgerEntry.record(
                payment,
                null,
                "payment-reconciliation:" + payment.getPaymentId() + ":" + attempt.getAttemptNo(),
                Type.PAYMENT_RECONCILIATION_REQUIRED,
                0L,
                now
        ));
    }

    private PaymentPreparation toPreparation(Payment payment) {
        return new PaymentPreparation(
                payment.getPaymentId(),
                payment.getPortOnePaymentId(),
                payment.getOrderName(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getSourceExpiresAt(),
                PaymentStatus.valueOf(payment.getStatus().name())
        );
    }

    private VerifiedWaitingReservationDeposit toVerifiedWaitingReservationDeposit(
            Payment payment
    ) {
        return new VerifiedWaitingReservationDeposit(
                payment.getPaymentId(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getSourcePolicyVersion(),
                PaymentStatus.valueOf(payment.getStatus().name()),
                payment.getPaidAt());
    }

    private PaymentResult toResult(Payment payment) {
        List<RefundSummary> summaries = refunds.findByPayment_IdOrderByRequestedAtAsc(payment.getId())
                .stream()
                .map(refund -> new RefundSummary(
                        refund.getRefundId(),
                        refund.getAmountMinor(),
                        refund.getCurrency(),
                        refund.getStatus(),
                        refund.getRequestedAt(),
                        refund.getCompletedAt()
                ))
                .toList();
        return new PaymentResult(
                payment.getPaymentId(),
                payment.getSourceReferenceId(),
                payment.getAmountMinor(),
                payment.getRefundedAmountMinor(),
                payment.getRefundableAmountMinor(),
                payment.getCurrency(),
                PaymentStatus.valueOf(payment.getStatus().name()),
                PaymentAttemptStatus.valueOf(payment.getLastAttemptStatus().name()),
                payment.getCreatedAt(),
                payment.getPaidAt(),
                payment.getUpdatedAt(),
                summaries
        );
    }

    private StoreReservationPaymentSnapshot toStoreReservationPaymentSnapshot(Payment payment) {
        List<StoreReservationRefundSnapshot> snapshots =
                refunds.findByPayment_IdOrderByRequestedAtAsc(payment.getId())
                        .stream()
                        .sorted(Comparator.comparing(PaymentRefund::getRequestedAt)
                                .thenComparing(PaymentRefund::getRefundId))
                        .map(refund -> new StoreReservationRefundSnapshot(
                                refund.getRefundId(),
                                refund.getAmountMinor(),
                                refund.getStatus(),
                                refund.getRequestedAt(),
                                refund.getCompletedAt()))
                        .toList();
        return new StoreReservationPaymentSnapshot(
                payment.getPaymentId(),
                payment.getAmountMinor(),
                payment.getRefundedAmountMinor(),
                payment.getRefundableAmountMinor(),
                payment.getCurrency(),
                PaymentStatus.valueOf(payment.getStatus().name()),
                PaymentAttemptStatus.valueOf(payment.getLastAttemptStatus().name()),
                payment.getCreatedAt(),
                payment.getPaidAt(),
                payment.getUpdatedAt(),
                snapshots);
    }

    private RefundResult toRefundResult(PaymentRefund refund) {
        return new RefundResult(
                refund.getRefundId(),
                refund.getPayment().getPaymentId(),
                refund.getAmountMinor(),
                refund.getStatus() == RefundStatus.COMPLETED ? refund.getAmountMinor() : 0L,
                refund.getPayment().getRefundedAmountMinor(),
                refund.getPayment().getRefundableAmountMinor(),
                refund.getCurrency(),
                refund.getStatus(),
                refund.getRequestedAt(),
                refund.getCompletedAt()
        );
    }

    private DispositionResult toDispositionResult(ReservationDepositDisposition disposition) {
        return new DispositionResult(
                disposition.getDispositionId(),
                disposition.getPayment().getPaymentId(),
                disposition.getSourceEventId(),
                disposition.getSourceEventType(),
                disposition.getCorrectsSourceEventId(),
                disposition.getPolicyVersion(),
                disposition.getResponsibilityCode(),
                disposition.getTargetRefundRateBasisPoints(),
                disposition.getOriginalAmountMinor(),
                disposition.getTargetRefundAmountMinor(),
                disposition.getIncrementalRefundAmountMinor(),
                disposition.getCompletedRefundAmountMinor(),
                disposition.getWithheldAmountMinor(),
                disposition.getCurrency(),
                disposition.getRefundId(),
                disposition.getStatus(),
                disposition.getFailureClassification(),
                disposition.getRequestedAt(),
                disposition.getUpdatedAt(),
                disposition.getCompletedAt()
        );
    }

    private static String refundFingerprint(RequestRefundCommand command) {
        String canonical = command.paymentId() + "\n" + command.sourceEventId() + "\n"
                + command.refundAmountMinor() + "\n" + command.reasonCode()
                + "\n" + command.policyVersion();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String dispositionFingerprint(
            ApplyReservationDepositDispositionCommand command
    ) {
        String canonical = command.paymentId() + "\n" + command.sourceEventId() + "\n"
                + command.sourceEventType() + "\n" + command.correctsSourceEventId() + "\n"
                + command.policyVersion() + "\n" + command.responsibilityCode() + "\n"
                + command.targetRefundRateBasisPoints();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static long dispositionTargetAmount(long originalAmountMinor, int rateBasisPoints) {
        return switch (rateBasisPoints) {
            case 0 -> 0L;
            case 5000 -> originalAmountMinor / 2L;
            case 10000 -> originalAmountMinor;
            default -> throw new IllegalArgumentException("unsupported target refund rate");
        };
    }

    private static String preparationFingerprint(PrepareReservationDepositCommand command) {
        return preparationFingerprint(
                command.sourceReferenceId(),
                command.consumerAccountId(),
                command.amountMinor(),
                command.currency(),
                command.sourceExpiresAt(),
                command.sourcePolicyVersion()
        );
    }

    private static String preparationFingerprint(PrepareWaitingReservationDepositCommand command) {
        return preparationFingerprint(
                command.sourceReferenceId(),
                command.consumerAccountId(),
                command.amountMinor(),
                command.currency(),
                command.sourceExpiresAt(),
                command.sourcePolicyVersion()
        );
    }

    private static String preparationFingerprint(
            String sourceReferenceId,
            long consumerAccountId,
            long amountMinor,
            String currency,
            Instant sourceExpiresAt,
            long sourcePolicyVersion
    ) {
        return sha256(sourceReferenceId + "\n" + consumerAccountId
                + "\n" + amountMinor + "\n" + currency
                + "\n" + sourceExpiresAt.truncatedTo(ChronoUnit.MICROS)
                + "\n" + sourcePolicyVersion);
    }

    private static String confirmationFingerprint(ConfirmPaymentCommand command) {
        String canonical = command.paymentId() + "\n" + command.consumerAccountId()
                + "\n" + command.portOnePaymentId();
        return sha256(canonical);
    }

    private static String historyFingerprint(String secret, PaymentHistoryQuery query) {
        String principalScope = hmacSha256(
                secret,
                "consumer:" + query.consumerAccountId()
        );
        return "v1:principal=" + principalScope
                + ":status=" + (query.status() == null ? "ALL" : query.status().name());
    }

    private static String hmacSha256(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is not available", exception);
        }
    }

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
