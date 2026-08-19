package com.miriyum.domain.payment.entity;

import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

/** 하나의 멱등 환불 요청과 외부 취소 결과를 보존한다. */
@Entity
@Table(name = "payment_refunds")
public class PaymentRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_refund_pk")
    private Long id;

    @Column(name = "refund_id", nullable = false, unique = true, length = 19)
    private String refundId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_pk", nullable = false)
    private Payment payment;

    @Column(name = "idempotency_key", nullable = false, length = 36)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "source_event_id", nullable = false, length = 100)
    private String sourceEventId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RefundStatus status;

    @Column(name = "reason_code", nullable = false, length = 40)
    private String reasonCode;

    @Column(name = "policy_version", nullable = false)
    private long policyVersion;

    @Column(name = "provider_cancellation_id", unique = true, length = 255)
    private String providerCancellationId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "processing_started_at", nullable = false)
    private Instant processingStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentRefund() {
    }

    private PaymentRefund(
            String refundId,
            Payment payment,
            String idempotencyKey,
            String requestFingerprint,
            String sourceEventId,
            long amountMinor,
            String reasonCode,
            long policyVersion,
            Instant requestedAt
    ) {
        this.refundId = Objects.requireNonNull(refundId);
        this.payment = Objects.requireNonNull(payment);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.requestFingerprint = Objects.requireNonNull(requestFingerprint);
        this.sourceEventId = Objects.requireNonNull(sourceEventId);
        if (amountMinor <= 0 || amountMinor > payment.getRefundableAmountMinor()) {
            throw new IllegalArgumentException("refund amount exceeds refundable amount");
        }
        this.amountMinor = amountMinor;
        this.currency = payment.getCurrency();
        this.reasonCode = Objects.requireNonNull(reasonCode);
        if (policyVersion <= 0) {
            throw new IllegalArgumentException("policyVersion must be positive");
        }
        this.policyVersion = policyVersion;
        this.status = RefundStatus.PROCESSING;
        this.attemptCount = 1;
        this.requestedAt = Objects.requireNonNull(requestedAt);
        this.processingStartedAt = requestedAt;
        this.updatedAt = requestedAt;
    }

    public static PaymentRefund request(
            String refundId,
            Payment payment,
            String idempotencyKey,
            String requestFingerprint,
            String sourceEventId,
            long amountMinor,
            String reasonCode,
            long policyVersion,
            Instant requestedAt
    ) {
        return new PaymentRefund(
                refundId, payment, idempotencyKey, requestFingerprint, sourceEventId,
                amountMinor, reasonCode, policyVersion, requestedAt);
    }

    public void complete(String cancellationId, Instant completedAt) {
        if (status == RefundStatus.COMPLETED) {
            return;
        }
        this.providerCancellationId = Objects.requireNonNull(cancellationId);
        this.status = RefundStatus.COMPLETED;
        this.completedAt = Objects.requireNonNull(completedAt);
        this.updatedAt = completedAt;
    }

    public void requireReconciliation(Instant detectedAt) {
        if (status == RefundStatus.COMPLETED) {
            return;
        }
        this.status = RefundStatus.RECONCILIATION_REQUIRED;
        this.updatedAt = Objects.requireNonNull(detectedAt);
    }

    public void fail(Instant failedAt) {
        if (status == RefundStatus.COMPLETED) {
            return;
        }
        this.status = RefundStatus.FAILED;
        this.updatedAt = Objects.requireNonNull(failedAt);
    }

    /** 명시적으로 실패한 동일 provider 취소 참조를 새 환불 생성 없이 다시 선점한다. */
    public void retry(Instant retriedAt) {
        if (status != RefundStatus.FAILED) {
            throw new IllegalStateException("only an explicitly failed refund can be retried");
        }
        this.status = RefundStatus.PROCESSING;
        this.attemptCount++;
        this.processingStartedAt = Objects.requireNonNull(retriedAt);
        this.updatedAt = Objects.requireNonNull(retriedAt);
    }

    public Long getId() { return id; }
    public String getRefundId() { return refundId; }
    public Payment getPayment() { return payment; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public String getSourceEventId() { return sourceEventId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public long getVersion() { return version; }
    public RefundStatus getStatus() { return status; }
    public String getReasonCode() { return reasonCode; }
    public long getPolicyVersion() { return policyVersion; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getProcessingStartedAt() { return processingStartedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public int getAttemptCount() { return attemptCount; }
}
