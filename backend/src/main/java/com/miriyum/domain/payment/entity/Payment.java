package com.miriyum.domain.payment.entity;

import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;

/** 결제의 현재 스냅샷과 상태 전이 불변식을 소유하는 aggregate root다. */
@Entity
@Table(name = "payments")
public class Payment {

    private static final Pattern PUBLIC_ID = Pattern.compile("^[1-9][0-9]{0,18}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern PORTONE_PAYMENT_ID = Pattern.compile("^[A-Za-z0-9_-]{6,64}$");

    public enum Status {
        READY,
        CONFIRMING,
        PAID,
        PARTIALLY_REFUNDED,
        REFUNDED,
        RECONCILIATION_REQUIRED
    }

    public enum AttemptStatus {
        NOT_STARTED,
        PENDING,
        PAID,
        FAILED,
        CANCELLED,
        UNKNOWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_pk")
    private Long id;

    @Column(name = "payment_id", nullable = false, unique = true, length = 19)
    private String paymentId;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "source_reference_id", nullable = false, length = 19)
    private String sourceReferenceId;

    @Column(name = "store_id", nullable = false)
    private long storeId;

    @Column(name = "source_policy_version", nullable = false)
    private long sourcePolicyVersion;

    @Column(name = "source_expires_at", nullable = false)
    private Instant sourceExpiresAt;

    @Column(name = "preparation_idempotency_key", nullable = false, length = 36)
    private String preparationIdempotencyKey;

    @Column(name = "preparation_request_fingerprint", nullable = false, length = 64)
    private String preparationRequestFingerprint;

    @Column(name = "consumer_account_id", nullable = false)
    private Long consumerAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "portone_payment_id", nullable = false, unique = true, length = 64)
    private String portOnePaymentId;

    @Column(name = "order_name", nullable = false, length = 100)
    private String orderName;

    @Column(name = "provider_transaction_id", unique = true, length = 255)
    private String providerTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_attempt_status", nullable = false, length = 20)
    private AttemptStatus lastAttemptStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Payment() {
    }

    private Payment(
            String paymentId,
            String sourceType,
            String sourceReferenceId,
            long storeId,
            long sourcePolicyVersion,
            Instant sourceExpiresAt,
            String preparationIdempotencyKey,
            String preparationRequestFingerprint,
            Long consumerAccountId,
            long amountMinor,
            String currency,
            String portOnePaymentId,
            String orderName,
            Instant createdAt
    ) {
        this.paymentId = requirePublicId(paymentId, "paymentId");
        this.sourceType = requireText(sourceType, 40, "sourceType");
        this.sourceReferenceId = requirePublicId(sourceReferenceId, "sourceReferenceId");
        this.storeId = requirePositive(storeId, "storeId");
        this.sourcePolicyVersion = requirePositive(sourcePolicyVersion, "sourcePolicyVersion");
        this.sourceExpiresAt = Objects.requireNonNull(
                sourceExpiresAt, "sourceExpiresAt must not be null").truncatedTo(ChronoUnit.MICROS);
        this.preparationIdempotencyKey = requireText(
                preparationIdempotencyKey, 36, "preparationIdempotencyKey");
        this.preparationRequestFingerprint = requireText(
                preparationRequestFingerprint, 64, "preparationRequestFingerprint");
        this.consumerAccountId = requirePositive(consumerAccountId, "consumerAccountId");
        this.amountMinor = requirePositive(amountMinor, "amountMinor");
        this.currency = requirePattern(currency, CURRENCY, "currency");
        this.portOnePaymentId = requirePattern(
                portOnePaymentId,
                PORTONE_PAYMENT_ID,
                "portOnePaymentId"
        );
        this.orderName = requireText(orderName, 100, "orderName");
        this.refundedAmountMinor = 0L;
        this.status = Status.READY;
        this.lastAttemptStatus = AttemptStatus.NOT_STARTED;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!this.sourceExpiresAt.isAfter(this.createdAt)) {
            throw new IllegalArgumentException("sourceExpiresAt must be after createdAt");
        }
        this.updatedAt = createdAt;
    }

    public static Payment prepare(
            String paymentId,
            String sourceType,
            String sourceReferenceId,
            long storeId,
            long sourcePolicyVersion,
            Instant sourceExpiresAt,
            String preparationIdempotencyKey,
            String preparationRequestFingerprint,
            Long consumerAccountId,
            long amountMinor,
            String currency,
            String portOnePaymentId,
            String orderName,
            Instant createdAt
    ) {
        return new Payment(
                paymentId,
                sourceType,
                sourceReferenceId,
                storeId,
                sourcePolicyVersion,
                sourceExpiresAt,
                preparationIdempotencyKey,
                preparationRequestFingerprint,
                consumerAccountId,
                amountMinor,
                currency,
                portOnePaymentId,
                orderName,
                createdAt
        );
    }

    public void beginConfirmation(Instant confirmedAt) {
        Instant now = requireNotBeforeCreation(confirmedAt, "confirmedAt");
        if (status == Status.CONFIRMING) {
            return;
        }
        if (status != Status.READY && status != Status.RECONCILIATION_REQUIRED) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        status = Status.CONFIRMING;
        lastAttemptStatus = AttemptStatus.PENDING;
        updatedAt = now;
    }

    public void markPaid(String transactionId, Instant confirmedPaidAt) {
        String validatedTransactionId = requireText(transactionId, 255, "transactionId");
        Instant now = requireNotBeforeCreation(confirmedPaidAt, "paidAt");
        if (status == Status.PAID || status == Status.PARTIALLY_REFUNDED || status == Status.REFUNDED) {
            if (validatedTransactionId.equals(providerTransactionId)) {
                return;
            }
            throw new ServiceException(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);
        }
        if (status != Status.CONFIRMING) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        providerTransactionId = validatedTransactionId;
        status = Status.PAID;
        lastAttemptStatus = AttemptStatus.PAID;
        paidAt = now;
        updatedAt = now;
    }

    public void markFailed(Instant failedAt) {
        Instant now = requireNotBeforeCreation(failedAt, "failedAt");
        if (status != Status.CONFIRMING) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        status = Status.READY;
        lastAttemptStatus = AttemptStatus.FAILED;
        updatedAt = now;
    }

    public void markCancelled(Instant cancelledAt) {
        Instant now = requireNotBeforeCreation(cancelledAt, "cancelledAt");
        if (status != Status.CONFIRMING) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        status = Status.READY;
        lastAttemptStatus = AttemptStatus.CANCELLED;
        updatedAt = now;
    }

    public void markReconciliationRequired(Instant detectedAt) {
        Instant now = requireNotBeforeCreation(detectedAt, "detectedAt");
        if (status != Status.CONFIRMING && status != Status.PAID
                && status != Status.PARTIALLY_REFUNDED) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        status = Status.RECONCILIATION_REQUIRED;
        lastAttemptStatus = AttemptStatus.UNKNOWN;
        updatedAt = now;
    }

    public void markRefundReconciliationRequired(Instant detectedAt) {
        Instant now = requireNotBeforeCreation(detectedAt, "detectedAt");
        if (status != Status.READY && status != Status.CONFIRMING && status != Status.PAID
                && status != Status.PARTIALLY_REFUNDED
                && status != Status.REFUNDED
                && status != Status.RECONCILIATION_REQUIRED) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        status = Status.RECONCILIATION_REQUIRED;
        if (lastAttemptStatus == AttemptStatus.PENDING) {
            lastAttemptStatus = AttemptStatus.UNKNOWN;
        }
        updatedAt = now;
    }

    /** 불명확했던 환불이 명시 실패로 해소되면 실제 완료 환불액 기준 상태로 복구한다. */
    public void restoreRefundableStatusAfterReconciliation(Instant resolvedAt) {
        Instant now = requireNotBeforeCreation(resolvedAt, "resolvedAt");
        if (status != Status.RECONCILIATION_REQUIRED) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        status = refundedAmountMinor == 0L ? Status.PAID : Status.PARTIALLY_REFUNDED;
        updatedAt = now;
    }

    public void applyCompletedRefund(long refundAmountMinor, Instant completedAt) {
        long validatedAmount = requirePositive(refundAmountMinor, "refundAmountMinor");
        Instant now = requireNotBeforeCreation(completedAt, "completedAt");
        if (status != Status.PAID && status != Status.PARTIALLY_REFUNDED
                && status != Status.RECONCILIATION_REQUIRED) {
            throw new ServiceException(PaymentErrorCode.INVALID_STATE_TRANSITION);
        }
        if (validatedAmount > getRefundableAmountMinor()) {
            throw new IllegalArgumentException("refundAmountMinor exceeds refundable amount");
        }
        refundedAmountMinor += validatedAmount;
        status = refundedAmountMinor == amountMinor ? Status.REFUNDED : Status.PARTIALLY_REFUNDED;
        updatedAt = now;
    }

    private Instant requireNotBeforeCreation(Instant value, String fieldName) {
        Instant instant = Objects.requireNonNull(value, fieldName + " must not be null");
        if (instant.isBefore(createdAt)) {
            throw new IllegalArgumentException(fieldName + " must not be before createdAt");
        }
        return instant;
    }

    private static String requirePublicId(String value, String fieldName) {
        return requirePattern(value, PUBLIC_ID, fieldName);
    }

    private static String requirePattern(String value, Pattern pattern, String fieldName) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " has an invalid format");
        }
        return value;
    }

    private static String requireText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must contain valid text");
        }
        return value;
    }

    private static Long requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceReferenceId() {
        return sourceReferenceId;
    }

    public long getStoreId() {
        return storeId;
    }

    public long getSourcePolicyVersion() {
        return sourcePolicyVersion;
    }

    public Instant getSourceExpiresAt() { return sourceExpiresAt; }

    public String getPreparationIdempotencyKey() { return preparationIdempotencyKey; }

    public String getPreparationRequestFingerprint() { return preparationRequestFingerprint; }

    public Long getConsumerAccountId() {
        return consumerAccountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public long getRefundedAmountMinor() {
        return refundedAmountMinor;
    }

    public long getRefundableAmountMinor() {
        return amountMinor - refundedAmountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPortOnePaymentId() {
        return portOnePaymentId;
    }

    public long getVersion() { return version; }

    public String getOrderName() { return orderName; }

    public String getProviderTransactionId() {
        return providerTransactionId;
    }

    public Status getStatus() {
        return status;
    }

    public AttemptStatus getLastAttemptStatus() {
        return lastAttemptStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }
}
