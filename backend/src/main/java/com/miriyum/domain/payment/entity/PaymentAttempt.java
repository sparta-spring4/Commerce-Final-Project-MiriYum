package com.miriyum.domain.payment.entity;

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
import java.time.Instant;
import java.util.Objects;

/** 외부 결제 조회 시도별 결과를 추가 전용으로 보존한다. */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_attempt_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_pk", nullable = false)
    private Payment payment;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "principal_id", nullable = false)
    private Long principalId;

    @Column(name = "idempotency_key", nullable = false, length = 36)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Payment.AttemptStatus status;

    @Column(name = "provider_transaction_id", unique = true, length = 255)
    private String providerTransactionId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected PaymentAttempt() {
    }

    private PaymentAttempt(
            Payment payment,
            int attemptNo,
            Long principalId,
            String idempotencyKey,
            String requestFingerprint,
            Instant startedAt
    ) {
        this.payment = Objects.requireNonNull(payment, "payment must not be null");
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        this.attemptNo = attemptNo;
        if (principalId == null || principalId <= 0) {
            throw new IllegalArgumentException("principalId must be positive");
        }
        this.principalId = principalId;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.requestFingerprint = Objects.requireNonNull(requestFingerprint);
        this.status = Payment.AttemptStatus.PENDING;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
    }

    public static PaymentAttempt start(
            Payment payment,
            int attemptNo,
            Long principalId,
            String idempotencyKey,
            String requestFingerprint,
            Instant startedAt
    ) {
        return new PaymentAttempt(
                payment, attemptNo, principalId, idempotencyKey, requestFingerprint, startedAt);
    }

    public void finish(Payment.AttemptStatus status, String transactionId, Instant finishedAt) {
        if (this.status != Payment.AttemptStatus.PENDING) {
            return;
        }
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.providerTransactionId = transactionId;
        this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt must not be null");
    }

    public Long getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public Payment.AttemptStatus getStatus() {
        return status;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getStartedAt() {
        return startedAt;
    }
}
