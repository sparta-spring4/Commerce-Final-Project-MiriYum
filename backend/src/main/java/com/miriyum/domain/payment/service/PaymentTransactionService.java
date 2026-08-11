package com.miriyum.domain.payment.service;

import com.miriyum.domain.payment.dto.PaymentContracts.ConfirmPaymentCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistoryQuery;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistorySlice;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentAttemptStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.config.PaymentSettings;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundSummary;
import com.miriyum.domain.payment.entity.Payment;
import com.miriyum.domain.payment.entity.PaymentAttempt;
import com.miriyum.domain.payment.entity.PaymentLedgerEntry;
import com.miriyum.domain.payment.entity.PaymentLedgerEntry.Type;
import com.miriyum.domain.payment.entity.PaymentRefund;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderCancellation;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderPayment;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderStatus;
import com.miriyum.domain.payment.repository.PaymentAttemptRepository;
import com.miriyum.domain.payment.repository.PaymentLedgerEntryRepository;
import com.miriyum.domain.payment.repository.PaymentReferenceAllocator;
import com.miriyum.domain.payment.repository.PaymentRefundRepository;
import com.miriyum.domain.payment.repository.PaymentRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
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
            RefundResult completedResult
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
                    amountMinor, currency, reasonCode, null);
        }

        public static RefundClaim completed(RefundResult result) {
            return new RefundClaim(
                    false, result.refundId(), result.paymentId(), null,
                    result.requestedAmountMinor(), result.currency(), null, result);
        }
    }

    private static final String RESERVATION_DEPOSIT = "RESERVATION_DEPOSIT";

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final PaymentRefundRepository refunds;
    private final PaymentLedgerEntryRepository ledger;
    private final PaymentReferenceAllocator references;
    private final PaymentSettings settings;
    private final Clock clock;

    public PaymentTransactionService(
            PaymentRepository payments,
            PaymentAttemptRepository attempts,
            PaymentRefundRepository refunds,
            PaymentLedgerEntryRepository ledger,
            PaymentReferenceAllocator references,
            PaymentSettings settings,
            Clock clock
    ) {
        this.payments = payments;
        this.attempts = attempts;
        this.refunds = refunds;
        this.ledger = ledger;
        this.references = references;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public PaymentPreparation prepare(PrepareReservationDepositCommand command, Instant now) {
        if (!command.sourceExpiresAt().isAfter(now)) {
            throw new ServiceException(PaymentErrorCode.SOURCE_EXPIRED);
        }
        String fingerprint = preparationFingerprint(command);
        Payment idempotent = payments.findBySourceTypeAndPreparationIdempotencyKey(
                RESERVATION_DEPOSIT,
                command.idempotencyKey()
        ).orElse(null);
        if (idempotent != null) {
            if (idempotent.getPreparationRequestFingerprint().equals(fingerprint)) {
                return toPreparation(idempotent);
            }
            throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        Payment existing = payments.findBySourceTypeAndSourceReferenceId(
                RESERVATION_DEPOSIT,
                command.sourceReferenceId()
        ).orElse(null);
        if (existing != null) {
            throw new ServiceException(PaymentErrorCode.ACTIVE_SOURCE_CONFLICT);
        }

        String paymentId = references.nextPaymentId();
        Payment payment = Payment.prepare(
                paymentId,
                RESERVATION_DEPOSIT,
                command.sourceReferenceId(),
                command.sourcePolicyVersion(),
                command.sourceExpiresAt(),
                command.idempotencyKey(),
                fingerprint,
                command.consumerAccountId(),
                command.amountMinor(),
                command.currency(),
                "payment-reservation-" + paymentId,
                "MiriYum 예약금 " + command.sourceReferenceId(),
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
        String fingerprint = preparationFingerprint(command);
        Payment idempotent = payments.findBySourceTypeAndPreparationIdempotencyKey(
                RESERVATION_DEPOSIT,
                command.idempotencyKey()
        ).orElse(null);
        if (idempotent != null) {
            if (idempotent.getPreparationRequestFingerprint().equals(fingerprint)) {
                return toPreparation(idempotent);
            }
            throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (payments.findBySourceTypeAndSourceReferenceId(
                RESERVATION_DEPOSIT, command.sourceReferenceId()).isPresent()) {
            throw new ServiceException(PaymentErrorCode.ACTIVE_SOURCE_CONFLICT);
        }
        throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
    }

    @Transactional
    public ConfirmationClaim claimConfirmation(ConfirmPaymentCommand command, Instant now) {
        String fingerprint = confirmationFingerprint(command);
        PaymentAttempt idempotentAttempt = attempts.findByPrincipalIdAndIdempotencyKey(
                command.consumerAccountId(), command.idempotencyKey()).orElse(null);
        if (idempotentAttempt != null) {
            if (!idempotentAttempt.getRequestFingerprint().equals(fingerprint)) {
                throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            if (idempotentAttempt.getStatus() == Payment.AttemptStatus.PENDING) {
                throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
            }
            return ConfirmationClaim.completed(toResult(idempotentAttempt.getPayment()));
        }
        Payment payment = ownedPaymentForUpdate(command.paymentId(), command.consumerAccountId());
        if (!payment.getPortOnePaymentId().equals(command.portOnePaymentId())) {
            throw new ServiceException(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);
        }
        if (payment.getStatus() == Payment.Status.PAID
                || payment.getStatus() == Payment.Status.PARTIALLY_REFUNDED
                || payment.getStatus() == Payment.Status.REFUNDED) {
            return ConfirmationClaim.completed(toResult(payment));
        }
        if (payment.getStatus() == Payment.Status.CONFIRMING) {
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

    @Transactional
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

    @Transactional
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
            case PAY_PENDING -> {
                return toResult(payment);
            }
            case PARTIALLY_CANCELLED, UNKNOWN -> {
                payment.markReconciliationRequired(now);
                attempt.finish(Payment.AttemptStatus.UNKNOWN, providerPayment.transactionId(), now);
                recordPaymentReconciliation(payment, attempt, now);
            }
        }
        return toResult(payment);
    }

    @Transactional
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

    @Transactional
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

    @Transactional
    public RefundClaim claimRefund(RequestRefundCommand command, Instant now) {
        Payment payment = paymentForUpdate(command.paymentId());
        String fingerprint = refundFingerprint(command);
        PaymentRefund existing = refunds.findByPayment_IdAndIdempotencyKey(
                payment.getId(), command.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return RefundClaim.completed(toRefundResult(existing));
        }
        if (refunds.findByPayment_IdAndSourceEventId(
                payment.getId(), command.sourceEventId()).isPresent()) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        if (payment.getStatus() != Payment.Status.PAID
                && payment.getStatus() != Payment.Status.PARTIALLY_REFUNDED) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        if (command.refundAmountMinor() > payment.getRefundableAmountMinor()) {
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

    @Transactional
    public RefundResult finalizeRefund(
            RefundClaim claim,
            ProviderCancellation cancellation,
            Instant now
    ) {
        PaymentRefund refund = refundForUpdate(claim.refundId());
        if (refund.getStatus() == RefundStatus.COMPLETED) {
            return toRefundResult(refund);
        }
        Payment payment = paymentForUpdate(refund.getPayment().getPaymentId());
        if (cancellation.amountMinor() != refund.getAmountMinor()
                || !cancellation.currency().equals(refund.getCurrency())) {
            throw new ServiceException(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);
        }
        switch (cancellation.status()) {
            case CANCELLED, PARTIALLY_CANCELLED -> {
                payment.applyCompletedRefund(refund.getAmountMinor(), now);
                refund.complete(cancellation.cancellationId(), now);
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
                ledger.save(PaymentLedgerEntry.record(
                        payment,
                        refund,
                        "refund-failed:" + refund.getRefundId(),
                        Type.REFUND_FAILED,
                        refund.getAmountMinor(),
                        now
                ));
            }
            case PAY_PENDING, PAID, UNKNOWN -> markRefundReconciliation(refund, payment, now);
        }
        return toRefundResult(refund);
    }

    @Transactional
    public RefundResult markRefundUnknown(RefundClaim claim, Instant now) {
        PaymentRefund refund = refundForUpdate(claim.refundId());
        if (refund.getStatus() != RefundStatus.COMPLETED) {
            Payment payment = paymentForUpdate(refund.getPayment().getPaymentId());
            markRefundReconciliation(refund, payment, now);
        }
        return toRefundResult(refund);
    }

    @Transactional
    public RefundResult markRefundMismatch(RefundClaim claim, Instant now) {
        PaymentRefund refund = refundForUpdate(claim.refundId());
        if (refund.getStatus() != RefundStatus.COMPLETED) {
            Payment payment = paymentForUpdate(refund.getPayment().getPaymentId());
            markRefundReconciliation(refund, payment, now);
        }
        return toRefundResult(refund);
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
    public PaymentHistorySlice getConsumerPaymentHistory(PaymentHistoryQuery query) {
        String cursorSecret = settings.requireCursorSecret();
        String fingerprint = "status=" + (query.status() == null ? "ALL" : query.status().name());
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

    private PaymentAttempt latestAttempt(Payment payment) {
        return attempts.findFirstByPayment_IdOrderByAttemptNoDesc(payment.getId())
                .orElseThrow(() -> new IllegalStateException("confirmation attempt is missing"));
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

    private static String preparationFingerprint(PrepareReservationDepositCommand command) {
        return sha256(command.sourceReferenceId() + "\n" + command.consumerAccountId()
                + "\n" + command.amountMinor() + "\n" + command.currency()
                + "\n" + command.sourceExpiresAt().truncatedTo(ChronoUnit.MICROS)
                + "\n" + command.sourcePolicyVersion());
    }

    private static String confirmationFingerprint(ConfirmPaymentCommand command) {
        String canonical = command.paymentId() + "\n" + command.consumerAccountId()
                + "\n" + command.portOnePaymentId();
        return sha256(canonical);
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
