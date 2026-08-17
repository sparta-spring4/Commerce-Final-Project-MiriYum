package com.miriyum.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** Append-only evidence explaining why a deposit process entered compensation or recovery. */
@Entity
@Table(
        name = "reservation_deposit_cause_audits",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_deposit_cause_process_code",
                columnNames = {"reservation_deposit_process_id", "cause_code"}
        )
)
public class ReservationDepositCauseAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_deposit_cause_audit_id")
    private Long id;
    @Column(name = "reservation_deposit_process_id", nullable = false, updatable = false)
    private long reservationDepositProcessId;
    @Column(name = "cause_code", nullable = false, length = 40, updatable = false)
    private String causeCode;
    @Column(name = "payment_id", nullable = false, length = 64, updatable = false)
    private String paymentId;
    @Column(name = "payment_status", nullable = false, length = 32, updatable = false)
    private String paymentStatus;
    @Column(name = "paid_at", updatable = false)
    private Instant paidAt;
    @Column(name = "observed_at", nullable = false, updatable = false)
    private Instant observedAt;

    protected ReservationDepositCauseAudit() {
    }

    public static ReservationDepositCauseAudit record(
            long processId,
            String causeCode,
            String paymentId,
            String paymentStatus,
            Instant paidAt,
            Instant observedAt
    ) {
        if (processId <= 0) {
            throw new IllegalArgumentException("processId must be positive");
        }
        ReservationDepositCauseAudit audit = new ReservationDepositCauseAudit();
        audit.reservationDepositProcessId = processId;
        audit.causeCode = requireText(causeCode, 40, "causeCode");
        audit.paymentId = requireText(paymentId, 64, "paymentId");
        audit.paymentStatus = requireText(paymentStatus, 32, "paymentStatus");
        audit.paidAt = paidAt;
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt is required");
        }
        audit.observedAt = observedAt;
        return audit;
    }

    public boolean matches(String paymentId, String paymentStatus, Instant paidAt) {
        return this.paymentId.equals(paymentId)
                && this.paymentStatus.equals(paymentStatus)
                && java.util.Objects.equals(this.paidAt, paidAt);
    }

    private static String requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain valid text");
        }
        return value;
    }

    public Long getId() { return id; }
    public long getReservationDepositProcessId() { return reservationDepositProcessId; }
    public String getCauseCode() { return causeCode; }
    public String getPaymentId() { return paymentId; }
    public String getPaymentStatus() { return paymentStatus; }
    public Instant getPaidAt() { return paidAt; }
    public Instant getObservedAt() { return observedAt; }
}
