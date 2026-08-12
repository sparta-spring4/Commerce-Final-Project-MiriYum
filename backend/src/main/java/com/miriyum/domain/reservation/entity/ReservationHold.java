package com.miriyum.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/** 기존 확정 예약과 분리해 10분 임시 선점의 거래 스냅샷을 소유한다. */
@Entity
@Table(
        name = "reservation_holds",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_holds_creation_command",
                columnNames = {"consumer_account_id", "creation_command_id"}
        )
)
public class ReservationHold {

    private static final Duration HOLD_DURATION = Duration.ofMinutes(10);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_hold_id")
    private Long id;

    @Column(name = "consumer_account_id", nullable = false)
    private Long consumerAccountId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "store_name_snapshot", nullable = false, length = 100)
    private String storeNameSnapshot;

    @Embedded
    private ReservationTimeSnapshot timeSnapshot;

    @Embedded
    private PartyComposition party;

    @Embedded
    private ReservationContactSnapshot contactSnapshot;

    @Column(name = "capacity_policy_version", nullable = false)
    private long capacityPolicyVersion;

    @Column(name = "cancellation_policy_version", nullable = false)
    private Long cancellationPolicyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReservationHoldStatus status;

    @Version
    @Column(name = "status_version", nullable = false)
    private long statusVersion;

    @Column(name = "creation_command_id", nullable = false, length = 100)
    private String creationCommandId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected ReservationHold() {
    }

    private ReservationHold(
            Long consumerAccountId,
            Long storeId,
            String storeNameSnapshot,
            ReservationTimeSnapshot timeSnapshot,
            PartyComposition party,
            ReservationContactSnapshot contactSnapshot,
            long capacityPolicyVersion,
            ReservationCancellationPolicyVersion cancellationPolicyVersion,
            String creationCommandId,
            Instant createdAt
    ) {
        this.consumerAccountId = requirePositive(consumerAccountId, "consumerAccountId");
        this.storeId = requirePositive(storeId, "storeId");
        this.storeNameSnapshot = requireStoreName(storeNameSnapshot);
        this.timeSnapshot = requireNonNull(timeSnapshot, "timeSnapshot");
        if (!this.timeSnapshot.hasResolvedTime()
                || !this.timeSnapshot.belongsToStore(this.storeId)) {
            throw new IllegalArgumentException(
                    "resolved timeSnapshot must belong to the hold store"
            );
        }
        this.party = requireNonNull(party, "party");
        this.contactSnapshot = requireNonNull(contactSnapshot, "contactSnapshot");
        if (!this.contactSnapshot.isContactAvailableAtConfirmation()) {
            throw new IllegalArgumentException("contactSnapshot must be contactable");
        }
        this.capacityPolicyVersion = requirePositive(
                capacityPolicyVersion,
                "capacityPolicyVersion"
        );
        this.cancellationPolicyVersion = requireNonNull(
                cancellationPolicyVersion,
                "cancellationPolicyVersion"
        ).value();
        this.status = ReservationHoldStatus.ACTIVE;
        this.creationCommandId = requireCommandId(creationCommandId);
        this.createdAt = requireNonNull(createdAt, "createdAt");
        this.expiresAt = this.createdAt.plus(HOLD_DURATION);
    }

    /** 승인된 스냅샷으로 외부에 아직 확정되지 않은 10분 선점을 만든다. */
    public static ReservationHold active(
            Long consumerAccountId,
            Long storeId,
            String storeNameSnapshot,
            ReservationTimeSnapshot timeSnapshot,
            PartyComposition party,
            ReservationContactSnapshot contactSnapshot,
            long capacityPolicyVersion,
            ReservationCancellationPolicyVersion cancellationPolicyVersion,
            String creationCommandId,
            Instant createdAt
    ) {
        return new ReservationHold(
                consumerAccountId,
                storeId,
                storeNameSnapshot,
                timeSnapshot,
                party,
                contactSnapshot,
                capacityPolicyVersion,
                cancellationPolicyVersion,
                creationCommandId,
                createdAt
        );
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

    private static String requireCommandId(String value) {
        if (value == null) {
            throw new IllegalArgumentException("creationCommandId must be 1 to 100 characters");
        }
        String normalized = value.trim();
        if (normalized.isBlank() || normalized.length() > 100) {
            throw new IllegalArgumentException("creationCommandId must be 1 to 100 characters");
        }
        return normalized;
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
        return timeSnapshot.getServiceDate();
    }

    public Instant getStartAt() {
        return timeSnapshot.getStartAt();
    }

    public Instant getServiceEndAt() {
        return timeSnapshot.getServiceEndAt();
    }

    public Instant getOccupancyEndAt() {
        return timeSnapshot.getOccupancyEndAt();
    }

    public String getTimeZoneId() {
        return timeSnapshot.getTimeZoneId();
    }

    public ReservationTimeSnapshot getTimeSnapshot() {
        return timeSnapshot;
    }

    public PartyComposition getParty() {
        return party;
    }

    public ReservationContactSnapshot getContactSnapshot() {
        return contactSnapshot;
    }

    public long getCapacityPolicyVersion() {
        return capacityPolicyVersion;
    }

    public Long getCancellationPolicyVersion() {
        return cancellationPolicyVersion;
    }

    public long getReservationTimePolicyVersion() {
        return timeSnapshot.getReservationTimePolicyVersion();
    }

    public ReservationHoldStatus getStatus() {
        return status;
    }

    public long getStatusVersion() {
        return statusVersion;
    }

    public String getCreationCommandId() {
        return creationCommandId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
