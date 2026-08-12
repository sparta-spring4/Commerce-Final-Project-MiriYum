package com.miriyum.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.Instant;

/** 실제 발송 구현과 분리된 만료 2분 전 경고 의무다. */
@Entity
@Table(
        name = "reservation_hold_warning_tasks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_hold_warning_tasks_hold",
                columnNames = "reservation_hold_id"
        )
)
public class ReservationHoldWarningTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_hold_warning_task_id")
    private Long id;

    @Column(name = "reservation_hold_id", nullable = false)
    private Long reservationHoldId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "warning_due_at", nullable = false)
    private Instant warningDueAt;

    protected ReservationHoldWarningTask() {
    }

    private ReservationHoldWarningTask(
            long reservationHoldId,
            Instant holdCreatedAt,
            Instant holdExpiresAt
    ) {
        if (reservationHoldId <= 0) {
            throw new IllegalArgumentException("reservationHoldId must be positive");
        }
        if (holdCreatedAt == null || holdExpiresAt == null) {
            throw new IllegalArgumentException("holdCreatedAt and holdExpiresAt are required");
        }
        if (!Duration.between(holdCreatedAt, holdExpiresAt).equals(Duration.ofMinutes(10))) {
            throw new IllegalArgumentException("hold expiration must be exactly ten minutes");
        }
        this.reservationHoldId = reservationHoldId;
        this.createdAt = holdCreatedAt;
        this.warningDueAt = holdExpiresAt.minus(Duration.ofMinutes(2));
    }

    /** 정확히 10분인 선점에 대해 만료 2분 전 경고 의무를 만든다. */
    public static ReservationHoldWarningTask schedule(
            long reservationHoldId,
            Instant holdCreatedAt,
            Instant holdExpiresAt
    ) {
        return new ReservationHoldWarningTask(
                reservationHoldId,
                holdCreatedAt,
                holdExpiresAt
        );
    }

    public Long getId() {
        return id;
    }

    public Long getReservationHoldId() {
        return reservationHoldId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getWarningDueAt() {
        return warningDueAt;
    }
}
