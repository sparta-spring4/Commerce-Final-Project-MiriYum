package com.miriyum.domain.payment.entity;

import com.miriyum.domain.payment.dto.PaymentContracts.DispositionFailureClassification;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionStatus;
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

/** 예약금 원승인에 적용한 목표 누적 환불률과 그 금전 결과를 추가 전용으로 보존한다. */
@Entity
@Table(name = "reservation_deposit_dispositions")
public class ReservationDepositDisposition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_deposit_disposition_pk")
    private Long id;

    @Column(name = "disposition_id", nullable = false, unique = true, length = 36)
    private String dispositionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_pk", nullable = false)
    private Payment payment;

    @Column(name = "source_event_id", nullable = false, length = 100)
    private String sourceEventId;

    @Column(name = "source_event_type", nullable = false, length = 40)
    private String sourceEventType;

    @Column(name = "corrects_source_event_id", length = 100)
    private String correctsSourceEventId;

    @Column(name = "policy_version", nullable = false)
    private long policyVersion;

    @Column(name = "responsibility_code", nullable = false, length = 32)
    private String responsibilityCode;

    @Column(name = "target_refund_rate_basis_points", nullable = false)
    private int targetRefundRateBasisPoints;

    @Column(name = "original_amount_minor", nullable = false)
    private long originalAmountMinor;

    @Column(name = "target_refund_amount_minor", nullable = false)
    private long targetRefundAmountMinor;

    @Column(name = "incremental_refund_amount_minor", nullable = false)
    private long incrementalRefundAmountMinor;

    @Column(name = "completed_refund_amount_minor", nullable = false)
    private long completedRefundAmountMinor;

    @Column(name = "withheld_amount_minor", nullable = false)
    private long withheldAmountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "refund_id", length = 19)
    private String refundId;

    @Column(name = "idempotency_key", nullable = false, length = 36)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DispositionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_classification", length = 16)
    private DispositionFailureClassification failureClassification;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ReservationDepositDisposition() {
    }

    private ReservationDepositDisposition(
            Payment payment,
            String dispositionId,
            String sourceEventId,
            String sourceEventType,
            String correctsSourceEventId,
            long policyVersion,
            String responsibilityCode,
            int targetRefundRateBasisPoints,
            long targetRefundAmountMinor,
            long completedRefundAmountMinor,
            String requestFingerprint,
            Instant requestedAt
    ) {
        this.payment = Objects.requireNonNull(payment, "payment must not be null");
        this.dispositionId = requireText(dispositionId, "dispositionId");
        this.idempotencyKey = dispositionId;
        this.sourceEventId = requireText(sourceEventId, "sourceEventId");
        this.sourceEventType = requireText(sourceEventType, "sourceEventType");
        this.correctsSourceEventId = correctsSourceEventId;
        if (policyVersion <= 0) {
            throw new IllegalArgumentException("policyVersion must be positive");
        }
        this.policyVersion = policyVersion;
        this.responsibilityCode = requireText(responsibilityCode, "responsibilityCode");
        if (targetRefundRateBasisPoints != 0
                && targetRefundRateBasisPoints != 5000
                && targetRefundRateBasisPoints != 10000) {
            throw new IllegalArgumentException("unsupported target refund rate");
        }
        this.targetRefundRateBasisPoints = targetRefundRateBasisPoints;
        this.originalAmountMinor = payment.getAmountMinor();
        long expectedTarget = targetAmount(
                originalAmountMinor, targetRefundRateBasisPoints);
        if (targetRefundAmountMinor != expectedTarget) {
            throw new IllegalArgumentException("target refund amount does not match rate");
        }
        if (completedRefundAmountMinor < 0 || completedRefundAmountMinor > originalAmountMinor) {
            throw new IllegalArgumentException("completed refund amount exceeds original amount");
        }
        this.targetRefundAmountMinor = targetRefundAmountMinor;
        this.completedRefundAmountMinor = completedRefundAmountMinor;
        this.incrementalRefundAmountMinor = Math.max(
                0L, targetRefundAmountMinor - completedRefundAmountMinor);
        this.withheldAmountMinor = originalAmountMinor - targetRefundAmountMinor;
        this.currency = payment.getCurrency();
        this.requestFingerprint = requireText(requestFingerprint, "requestFingerprint");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        this.updatedAt = requestedAt;
        if (incrementalRefundAmountMinor == 0) {
            this.status = DispositionStatus.COMPLETED;
            this.completedAt = requestedAt;
            this.attemptCount = 0;
        } else {
            this.status = DispositionStatus.PROCESSING;
            this.attemptCount = 1;
        }
    }

    /** 저장된 결제 snapshot에서 목표 금액과 기존 완료액의 차이만 실행하는 처분을 만든다. */
    public static ReservationDepositDisposition create(
            Payment payment,
            String dispositionId,
            String sourceEventId,
            String sourceEventType,
            String correctsSourceEventId,
            long policyVersion,
            String responsibilityCode,
            int targetRefundRateBasisPoints,
            long targetRefundAmountMinor,
            long completedRefundAmountMinor,
            String requestFingerprint,
            Instant requestedAt
    ) {
        return new ReservationDepositDisposition(
                payment, dispositionId, sourceEventId, sourceEventType,
                correctsSourceEventId, policyVersion, responsibilityCode,
                targetRefundRateBasisPoints, targetRefundAmountMinor,
                completedRefundAmountMinor, requestFingerprint, requestedAt);
    }

    /** provider 호출 전 결정적으로 거부된 처분도 원래 완료액을 고치지 않고 감사 기록한다. */
    public static ReservationDepositDisposition rejectPermanent(
            Payment payment,
            String dispositionId,
            String sourceEventId,
            String sourceEventType,
            String correctsSourceEventId,
            long policyVersion,
            String responsibilityCode,
            int targetRefundRateBasisPoints,
            long targetRefundAmountMinor,
            long completedRefundAmountMinor,
            String requestFingerprint,
            Instant rejectedAt
    ) {
        ReservationDepositDisposition disposition = new ReservationDepositDisposition(
                payment, dispositionId, sourceEventId, sourceEventType,
                correctsSourceEventId, policyVersion, responsibilityCode,
                targetRefundRateBasisPoints, targetRefundAmountMinor,
                completedRefundAmountMinor, requestFingerprint, rejectedAt);
        disposition.status = DispositionStatus.FAILED;
        disposition.failureClassification = DispositionFailureClassification.PERMANENT;
        disposition.attemptCount = 0;
        disposition.completedAt = null;
        return disposition;
    }

    /** sibling 환불이 잔액을 임시 점유하면 provider 호출 없이 같은 처분의 재시도를 보존한다. */
    public static ReservationDepositDisposition deferRetryable(
            Payment payment,
            String dispositionId,
            String sourceEventId,
            String sourceEventType,
            String correctsSourceEventId,
            long policyVersion,
            String responsibilityCode,
            int targetRefundRateBasisPoints,
            long targetRefundAmountMinor,
            long completedRefundAmountMinor,
            String requestFingerprint,
            Instant deferredAt
    ) {
        ReservationDepositDisposition disposition = new ReservationDepositDisposition(
                payment, dispositionId, sourceEventId, sourceEventType,
                correctsSourceEventId, policyVersion, responsibilityCode,
                targetRefundRateBasisPoints, targetRefundAmountMinor,
                completedRefundAmountMinor, requestFingerprint, deferredAt);
        disposition.failRetryableWithoutRefund(deferredAt);
        return disposition;
    }

    public void failRetryable(String refundId, Instant failedAt) {
        requireProcessing();
        this.refundId = requireText(refundId, "refundId");
        this.status = DispositionStatus.FAILED;
        this.failureClassification = DispositionFailureClassification.RETRYABLE;
        this.updatedAt = Objects.requireNonNull(failedAt, "failedAt must not be null");
    }

    public void retry(Instant retriedAt) {
        if (status != DispositionStatus.FAILED
                || failureClassification != DispositionFailureClassification.RETRYABLE) {
            throw new IllegalStateException("only an explicit retryable failure can be retried");
        }
        this.status = DispositionStatus.PROCESSING;
        this.failureClassification = null;
        this.attemptCount++;
        this.updatedAt = Objects.requireNonNull(retriedAt, "retriedAt must not be null");
    }

    public void rejectRetryPermanently(Instant rejectedAt) {
        if (status != DispositionStatus.FAILED
                || failureClassification != DispositionFailureClassification.RETRYABLE) {
            throw new IllegalStateException("only a retryable failure can be rejected");
        }
        this.failureClassification = DispositionFailureClassification.PERMANENT;
        this.updatedAt = Objects.requireNonNull(
                rejectedAt, "rejectedAt must not be null");
    }

    public void requireReconciliation(String refundId, Instant detectedAt) {
        requireProcessing();
        this.refundId = requireText(refundId, "refundId");
        this.status = DispositionStatus.RECONCILIATION_REQUIRED;
        this.failureClassification = DispositionFailureClassification.UNKNOWN;
        this.updatedAt = Objects.requireNonNull(detectedAt, "detectedAt must not be null");
    }

    public void attachRefund(String refundId, Instant observedAt) {
        requireProcessing();
        this.refundId = requireText(refundId, "refundId");
        this.updatedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    public void failPermanent(Instant failedAt) {
        requireProcessing();
        this.status = DispositionStatus.FAILED;
        this.failureClassification = DispositionFailureClassification.PERMANENT;
        this.updatedAt = Objects.requireNonNull(failedAt, "failedAt must not be null");
    }

    public void completeRefund(String refundId, long completedDeltaAmountMinor, Instant at) {
        requireProcessing();
        completeRefundInternal(refundId, completedDeltaAmountMinor, at);
    }

    public void completeReconciledRefund(
            String refundId,
            long completedDeltaAmountMinor,
            Instant at
    ) {
        requireReconciliation();
        completeRefundInternal(refundId, completedDeltaAmountMinor, at);
    }

    public void failReconciledRetryable(String refundId, Instant failedAt) {
        requireReconciliation();
        this.refundId = requireText(refundId, "refundId");
        this.status = DispositionStatus.FAILED;
        this.failureClassification = DispositionFailureClassification.RETRYABLE;
        this.updatedAt = Objects.requireNonNull(failedAt, "failedAt must not be null");
    }

    public void failRetryableWithoutRefund(Instant failedAt) {
        requireProcessing();
        if (refundId != null) {
            throw new IllegalStateException("a refund is already attached to the disposition");
        }
        this.status = DispositionStatus.FAILED;
        this.failureClassification = DispositionFailureClassification.RETRYABLE;
        this.attemptCount = Math.max(0, attemptCount - 1);
        this.updatedAt = Objects.requireNonNull(failedAt, "failedAt must not be null");
    }

    public void attachRetryableRefund(String refundId, Instant observedAt) {
        requireRetryableFailure();
        attachSameRefund(refundId);
        this.attemptCount = Math.max(1, attemptCount);
        this.updatedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    public void resumeRetryableRefund(String refundId, Instant observedAt) {
        requireRetryableFailure();
        attachSameRefund(refundId);
        this.status = DispositionStatus.PROCESSING;
        this.failureClassification = null;
        this.attemptCount++;
        this.updatedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    public void requireReconciliationFromRetryable(String refundId, Instant detectedAt) {
        requireRetryableFailure();
        attachSameRefund(refundId);
        this.status = DispositionStatus.RECONCILIATION_REQUIRED;
        this.failureClassification = DispositionFailureClassification.UNKNOWN;
        this.attemptCount = Math.max(1, attemptCount);
        this.updatedAt = Objects.requireNonNull(detectedAt, "detectedAt must not be null");
    }

    public void completeRetryableRefund(
            String refundId,
            long completedDeltaAmountMinor,
            Instant at
    ) {
        requireRetryableFailure();
        attachSameRefund(refundId);
        this.attemptCount = Math.max(1, attemptCount);
        completeRefundInternal(refundId, completedDeltaAmountMinor, at);
    }

    private void completeRefundInternal(
            String refundId,
            long completedDeltaAmountMinor,
            Instant at
    ) {
        long newCompletedAmount = Math.addExact(
                completedRefundAmountMinor, completedDeltaAmountMinor);
        if (newCompletedAmount != targetRefundAmountMinor) {
            throw new IllegalArgumentException("completed refund amount must equal target");
        }
        this.refundId = requireText(refundId, "refundId");
        this.completedRefundAmountMinor = newCompletedAmount;
        this.status = DispositionStatus.COMPLETED;
        this.failureClassification = null;
        this.completedAt = Objects.requireNonNull(at, "completedAt must not be null");
        this.updatedAt = at;
    }

    private void requireReconciliation() {
        if (status != DispositionStatus.RECONCILIATION_REQUIRED
                || failureClassification != DispositionFailureClassification.UNKNOWN) {
            throw new IllegalStateException("disposition does not require reconciliation");
        }
    }

    private void requireRetryableFailure() {
        if (status != DispositionStatus.FAILED
                || failureClassification != DispositionFailureClassification.RETRYABLE) {
            throw new IllegalStateException("disposition is not a retryable failure");
        }
    }

    private void attachSameRefund(String observedRefundId) {
        String validated = requireText(observedRefundId, "refundId");
        if (refundId != null && !refundId.equals(validated)) {
            throw new IllegalStateException("a different refund is already attached");
        }
        this.refundId = validated;
    }

    private void requireProcessing() {
        if (status != DispositionStatus.PROCESSING) {
            throw new IllegalStateException("disposition is not processing");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must contain text");
        }
        return value;
    }

    private static long targetAmount(long originalAmountMinor, int rateBasisPoints) {
        return switch (rateBasisPoints) {
            case 0 -> 0L;
            case 5000 -> originalAmountMinor / 2L;
            case 10000 -> originalAmountMinor;
            default -> throw new IllegalArgumentException("unsupported target refund rate");
        };
    }

    public Long getId() { return id; }
    public String getDispositionId() { return dispositionId; }
    public Payment getPayment() { return payment; }
    public String getSourceEventId() { return sourceEventId; }
    public String getSourceEventType() { return sourceEventType; }
    public String getCorrectsSourceEventId() { return correctsSourceEventId; }
    public long getPolicyVersion() { return policyVersion; }
    public String getResponsibilityCode() { return responsibilityCode; }
    public int getTargetRefundRateBasisPoints() { return targetRefundRateBasisPoints; }
    public long getOriginalAmountMinor() { return originalAmountMinor; }
    public long getTargetRefundAmountMinor() { return targetRefundAmountMinor; }
    public long getIncrementalRefundAmountMinor() { return incrementalRefundAmountMinor; }
    public long getCompletedRefundAmountMinor() { return completedRefundAmountMinor; }
    public long getWithheldAmountMinor() { return withheldAmountMinor; }
    public String getCurrency() { return currency; }
    public long getVersion() { return version; }
    public String getRefundId() { return refundId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public DispositionStatus getStatus() { return status; }
    public DispositionFailureClassification getFailureClassification() {
        return failureClassification;
    }
    public int getAttemptCount() { return attemptCount; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
