package com.miriyum.domain.reservation.entity;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Owns the transaction snapshot for a general visit reservation and its confirmed terminal state
 * transitions.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long id;

    @Column(name = "consumer_account_id", nullable = false)
    private Long consumerAccountId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "store_name_snapshot", nullable = false, length = 100)
    private String storeNameSnapshot;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Embedded
    private PartyComposition party;

    @Column(name = "capacity_policy_version", nullable = false)
    private long capacityPolicyVersion;

    @Column(name = "reservation_policy_version", nullable = false)
    private long reservationPolicyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    protected Reservation() {
    }

    private Reservation(
            Long consumerAccountId,
            Long storeId,
            String storeNameSnapshot,
            LocalDate serviceDate,
            LocalTime startTime,
            LocalTime endTime,
            PartyComposition party,
            long capacityPolicyVersion,
            long reservationPolicyVersion,
            Instant createdAt
    ) {
        this.consumerAccountId = requirePositive(consumerAccountId, "consumerAccountId");
        this.storeId = requirePositive(storeId, "storeId");
        this.storeNameSnapshot = requireStoreName(storeNameSnapshot);
        this.serviceDate = requireNonNull(serviceDate, "serviceDate");
        this.startTime = requireNonNull(startTime, "startTime");
        this.endTime = requireNonNull(endTime, "endTime");
        this.party = requireNonNull(party, "party");
        this.capacityPolicyVersion = requirePositive(capacityPolicyVersion, "capacityPolicyVersion");
        this.reservationPolicyVersion = requirePositive(
                reservationPolicyVersion,
                "reservationPolicyVersion"
        );
        this.status = ReservationStatus.CONFIRMED;
        this.createdAt = requireNonNull(createdAt, "createdAt");
    }

    /**
     * Creates a reservation immediately confirmed from an approved transaction snapshot.
     *
     * @throws IllegalArgumentException when a required value, ID, policy version, or store name is invalid
     */
    public static Reservation confirm(
            Long consumerAccountId,
            Long storeId,
            String storeNameSnapshot,
            LocalDate serviceDate,
            LocalTime startTime,
            LocalTime endTime,
            PartyComposition party,
            long capacityPolicyVersion,
            long reservationPolicyVersion,
            Instant createdAt
    ) {
        return new Reservation(
                consumerAccountId,
                storeId,
                storeNameSnapshot,
                serviceDate,
                startTime,
                endTime,
                party,
                capacityPolicyVersion,
                reservationPolicyVersion,
                createdAt
        );
    }

    /**
     * Transitions a confirmed reservation to its cancelled terminal state.
     *
     * @throws IllegalArgumentException when the cancellation time is null
     * @throws ServiceException when the reservation is not confirmed
     */
    public void cancel(Instant cancelledAt) {
        requireConfirmed();
        this.cancelledAt = requireNonNull(cancelledAt, "cancelledAt");
        this.status = ReservationStatus.CANCELLED;
    }

    /**
     * Transitions a confirmed reservation to its fulfilled terminal state.
     *
     * @throws IllegalArgumentException when the fulfillment time is null
     * @throws ServiceException when the reservation is not confirmed
     */
    public void fulfill(Instant fulfilledAt) {
        requireConfirmed();
        this.fulfilledAt = requireNonNull(fulfilledAt, "fulfilledAt");
        this.status = ReservationStatus.FULFILLED;
    }

    private void requireConfirmed() {
        if (status != ReservationStatus.CONFIRMED) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION);
        }
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

    private static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }

    private static String requireStoreName(String value) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException(
                    "storeNameSnapshot must contain between 1 and 100 characters"
            );
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public Long getConsumerAccountId() {
        return consumerAccountId;
    }

    public Long getStoreId() {
        return storeId;
    }

    public String getStoreNameSnapshot() {
        return storeNameSnapshot;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public PartyComposition getParty() {
        return party;
    }

    public long getCapacityPolicyVersion() {
        return capacityPolicyVersion;
    }

    public long getReservationPolicyVersion() {
        return reservationPolicyVersion;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }
}
