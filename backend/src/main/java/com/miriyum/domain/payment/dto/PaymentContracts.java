package com.miriyum.domain.payment.dto;

import java.time.Instant;
import java.util.List;

/** Payment가 Reservation과 Consumer에 공개하는 Service DTO 묶음이다. */
public final class PaymentContracts {

    private PaymentContracts() {
    }

    public enum PaymentStatus {
        READY,
        CONFIRMING,
        PAID,
        PARTIALLY_REFUNDED,
        REFUNDED,
        RECONCILIATION_REQUIRED
    }

    public enum PaymentAttemptStatus {
        NOT_STARTED,
        PENDING,
        PAID,
        FAILED,
        CANCELLED,
        UNKNOWN
    }

    public enum RefundStatus {
        REQUESTED,
        VALIDATING,
        PROCESSING,
        COMPLETED,
        FAILED,
        RECONCILIATION_REQUIRED
    }

    public record PrepareReservationDepositCommand(
            String sourceReferenceId,
            long consumerAccountId,
            long amountMinor,
            String currency,
            Instant sourceExpiresAt,
            long sourcePolicyVersion,
            String idempotencyKey
    ) {
        public PrepareReservationDepositCommand {
            requirePublicId(sourceReferenceId, "sourceReferenceId");
            requirePositive(consumerAccountId, "consumerAccountId");
            requirePositive(amountMinor, "amountMinor");
            requireCurrency(currency);
            if (sourceExpiresAt == null) {
                throw new IllegalArgumentException("sourceExpiresAt must not be null");
            }
            requirePositive(sourcePolicyVersion, "sourcePolicyVersion");
            requireIdempotencyKey(idempotencyKey);
        }
    }

    public record PrepareWaitingReservationDepositCommand(
            String sourceReferenceId,
            long consumerAccountId,
            long amountMinor,
            String currency,
            Instant sourceExpiresAt,
            long sourcePolicyVersion,
            String idempotencyKey
    ) {
        public PrepareWaitingReservationDepositCommand {
            requirePublicId(sourceReferenceId, "sourceReferenceId");
            requirePositive(consumerAccountId, "consumerAccountId");
            requirePositive(amountMinor, "amountMinor");
            requireCurrency(currency);
            if (sourceExpiresAt == null) {
                throw new IllegalArgumentException("sourceExpiresAt must not be null");
            }
            requirePositive(sourcePolicyVersion, "sourcePolicyVersion");
            requireIdempotencyKey(idempotencyKey);
        }
    }

    public record PaymentPreparation(
            String paymentId,
            String portOnePaymentId,
            String orderName,
            long amountMinor,
            String currency,
            Instant sourceExpiresAt,
            PaymentStatus status
    ) {
    }

    public record VerifiedWaitingReservationDeposit(
            String paymentId,
            long amountMinor,
            String currency,
            long sourcePolicyVersion,
            PaymentStatus status,
            Instant paidAt
    ) {
        public VerifiedWaitingReservationDeposit {
            requirePublicId(paymentId, "paymentId");
            requirePositive(amountMinor, "amountMinor");
            requireCurrency(currency);
            requirePositive(sourcePolicyVersion, "sourcePolicyVersion");
            if (status != PaymentStatus.PAID
                    && status != PaymentStatus.PARTIALLY_REFUNDED
                    && status != PaymentStatus.REFUNDED
                    && status != PaymentStatus.RECONCILIATION_REQUIRED) {
                throw new IllegalArgumentException("status must describe a historically paid payment");
            }
            if (paidAt == null) {
                throw new IllegalArgumentException("paidAt must not be null");
            }
        }
    }

    public record ConfirmPaymentCommand(
            String paymentId,
            long consumerAccountId,
            String portOnePaymentId,
            String idempotencyKey
    ) {
        public ConfirmPaymentCommand {
            requirePublicId(paymentId, "paymentId");
            requirePositive(consumerAccountId, "consumerAccountId");
            requirePortOnePaymentId(portOnePaymentId);
            if (idempotencyKey == null || !idempotencyKey.matches(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
                throw new IllegalArgumentException("idempotencyKey must be a normalized UUID");
            }
        }
    }

    public record RequestRefundCommand(
            String paymentId,
            String sourceEventId,
            long refundAmountMinor,
            String reasonCode,
            long policyVersion,
            String idempotencyKey
    ) {
        public RequestRefundCommand {
            requirePublicId(paymentId, "paymentId");
            requireText(sourceEventId, 100, "sourceEventId");
            requirePositive(refundAmountMinor, "refundAmountMinor");
            requireText(reasonCode, 40, "reasonCode");
            requirePositive(policyVersion, "policyVersion");
            requireIdempotencyKey(idempotencyKey);
        }
    }

    public record RefundResult(
            String refundId,
            String paymentId,
            long requestedAmountMinor,
            long completedAmountMinor,
            long cumulativeRefundedAmountMinor,
            long remainingRefundableAmountMinor,
            String currency,
            RefundStatus status,
            Instant requestedAt,
            Instant completedAt
    ) {
    }

    public record RefundSummary(
            String refundId,
            long amountMinor,
            String currency,
            RefundStatus status,
            Instant requestedAt,
            Instant completedAt
    ) {
    }

    public record PaymentResult(
            String paymentId,
            String reservationReferenceId,
            long amountMinor,
            long refundedAmountMinor,
            long refundableAmountMinor,
            String currency,
            PaymentStatus status,
            PaymentAttemptStatus lastAttemptStatus,
            Instant createdAt,
            Instant paidAt,
            Instant updatedAt,
            List<RefundSummary> refunds
    ) {
        public PaymentResult {
            refunds = List.copyOf(refunds);
        }
    }

    public record PaymentHistoryQuery(
            long consumerAccountId,
            PaymentStatus status,
            int size,
            String cursor
    ) {
        public PaymentHistoryQuery {
            requirePositive(consumerAccountId, "consumerAccountId");
            if (size < 1 || size > 100) {
                throw new IllegalArgumentException("size must be between 1 and 100");
            }
        }
    }

    public record PaymentHistorySlice(
            List<PaymentResult> items,
            String nextCursor,
            boolean hasNext
    ) {
        public PaymentHistorySlice {
            items = List.copyOf(items);
        }
    }

    private static void requirePublicId(String value, String fieldName) {
        if (value == null || !value.matches("^[1-9][0-9]{0,18}$")) {
            throw new IllegalArgumentException(fieldName + " must be a positive numeric string");
        }
    }

    private static void requirePortOnePaymentId(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9_-]{6,64}$")) {
            throw new IllegalArgumentException("portOnePaymentId has an invalid format");
        }
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || !value.matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            throw new IllegalArgumentException("idempotencyKey must be a normalized UUID");
        }
    }

    private static void requireCurrency(String value) {
        if (value == null || !value.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code");
        }
    }

    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private static void requireText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must contain valid text");
        }
    }
}
