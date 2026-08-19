package com.miriyum.domain.payment.entity;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

/** Payment가 소유하는 수동 복구 intake와 실행 fencing 행이다. */
@Entity
@Table(
        name = "payment_recovery_handoffs",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_payment_recovery_handoff_source",
                    columnNames = {"source_type", "source_id"}),
            @UniqueConstraint(
                    name = "uk_payment_recovery_handoff_registration",
                    columnNames = "registration_idempotency_key")
        })
public class PaymentRecoveryHandoff {

    public enum Status {
        AVAILABLE,
        CLAIMED,
        ACKNOWLEDGED
    }

    public enum OperationStatus {
        PROCESSING,
        SUCCEEDED,
        FAILED,
        UNKNOWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_recovery_handoff_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 48, updatable = false)
    private ManualRecoverySourceType sourceType;

    @Column(name = "source_id", nullable = false, length = 64, updatable = false)
    private String sourceId;

    @Column(name = "payment_pk", nullable = false, updatable = false)
    private long paymentPk;

    @Column(name = "payment_id", nullable = false, length = 64, updatable = false)
    private String paymentId;

    @Column(name = "source_event_id", nullable = false, length = 100, updatable = false)
    private String sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_kind", nullable = false, length = 40, updatable = false)
    private ManualRecoveryKind recoveryKind;

    @Column(name = "registration_idempotency_key", nullable = false,
            length = 36, updatable = false)
    private String registrationIdempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "claim_token", nullable = false)
    private long claimToken;

    @Column(name = "admin_case_id", length = 100)
    private String adminCaseId;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "operation_id", length = 36)
    private String operationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_status", length = 20)
    private OperationStatus operationStatus;

    @Column(name = "operation_started_at")
    private Instant operationStartedAt;

    @Column(name = "operation_finished_at")
    private Instant operationFinishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected PaymentRecoveryHandoff() {
    }

    public static PaymentRecoveryHandoff register(
            ManualRecoverySourceType sourceType,
            String sourceId,
            long paymentPk,
            String paymentId,
            String sourceEventId,
            ManualRecoveryKind recoveryKind,
            String registrationIdempotencyKey,
            Instant now
    ) {
        PaymentRecoveryHandoff handoff = new PaymentRecoveryHandoff();
        handoff.sourceType = Objects.requireNonNull(sourceType);
        handoff.sourceId = requireText(sourceId, 64, "sourceId");
        if (paymentPk <= 0) {
            throw new IllegalArgumentException("paymentPk must be positive");
        }
        handoff.paymentPk = paymentPk;
        handoff.paymentId = requireText(paymentId, 64, "paymentId");
        handoff.sourceEventId = requireText(sourceEventId, 100, "sourceEventId");
        handoff.recoveryKind = Objects.requireNonNull(recoveryKind);
        handoff.registrationIdempotencyKey = requireText(
                registrationIdempotencyKey, 36, "registrationIdempotencyKey");
        handoff.status = Status.AVAILABLE;
        handoff.createdAt = Objects.requireNonNull(now);
        handoff.updatedAt = now;
        return handoff;
    }

    public void claim(String owner, Instant now, Instant until) {
        if (owner == null || owner.isBlank() || owner.length() > 64
                || now == null || until == null || !until.isAfter(now)) {
            throw new IllegalArgumentException("lease fields must be valid");
        }
        boolean available = status == Status.AVAILABLE;
        boolean expired = status == Status.CLAIMED
                && leaseUntil != null
                && !leaseUntil.isAfter(now);
        if (!available && !expired) {
            throw new IllegalStateException("recovery handoff is not claimable");
        }
        status = Status.CLAIMED;
        claimToken++;
        leaseOwner = owner;
        leaseUntil = until;
        updatedAt = now;
    }

    public void acknowledge(String owner, long token, String caseId, Instant now) {
        String requiredCaseId = requireText(caseId, 100, "caseId");
        if (status == Status.ACKNOWLEDGED && requiredCaseId.equals(adminCaseId)) {
            return;
        }
        if (status != Status.CLAIMED
                || owner == null || !owner.equals(leaseOwner)
                || token != claimToken
                || now == null || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalStateException("stale recovery handoff claim");
        }
        status = Status.ACKNOWLEDGED;
        adminCaseId = requiredCaseId;
        acknowledgedAt = now;
        leaseOwner = null;
        leaseUntil = null;
        updatedAt = now;
    }

    public boolean matchesRegistration(
            long paymentPk,
            String paymentId,
            String sourceEventId,
            ManualRecoveryKind recoveryKind,
            String idempotencyKey
    ) {
        return this.paymentPk == paymentPk
                && Objects.equals(this.paymentId, paymentId)
                && Objects.equals(this.sourceEventId, sourceEventId)
                && this.recoveryKind == recoveryKind
                && Objects.equals(this.registrationIdempotencyKey, idempotencyKey);
    }

    private static String requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain valid text");
        }
        return value;
    }

    public Long getId() { return id; }
    public ManualRecoverySourceType getSourceType() { return sourceType; }
    public String getSourceId() { return sourceId; }
    public long getPaymentPk() { return paymentPk; }
    public String getPaymentId() { return paymentId; }
    public String getSourceEventId() { return sourceEventId; }
    public ManualRecoveryKind getRecoveryKind() { return recoveryKind; }
    public String getRegistrationIdempotencyKey() { return registrationIdempotencyKey; }
    public Status getStatus() { return status; }
    public long getClaimToken() { return claimToken; }
    public String getAdminCaseId() { return adminCaseId; }
    public long getRowVersion() { return rowVersion; }
}
