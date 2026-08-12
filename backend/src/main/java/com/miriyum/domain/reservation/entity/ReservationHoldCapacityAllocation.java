package com.miriyum.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 임시 선점이 수용량 버킷 하나에서 점유한 인원·팀 수 스냅샷이다. */
@Entity
@Table(
        name = "reservation_hold_capacity_allocations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_hold_capacity_allocations_hold_bucket",
                columnNames = {"reservation_hold_id", "reservation_capacity_bucket_id"}
        )
)
public class ReservationHoldCapacityAllocation {

    private static final int OCCUPIED_TEAMS_PER_HOLD = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_hold_capacity_allocation_id")
    private Long id;

    @Column(name = "reservation_hold_id", nullable = false)
    private Long reservationHoldId;

    @Column(name = "reservation_capacity_bucket_id", nullable = false)
    private Long capacityBucketId;

    @Column(name = "occupied_people", nullable = false)
    private int occupiedPeople;

    @Column(name = "occupied_teams", nullable = false)
    private int occupiedTeams;

    @Column(name = "capacity_policy_version", nullable = false)
    private long capacityPolicyVersion;

    protected ReservationHoldCapacityAllocation() {
    }

    private ReservationHoldCapacityAllocation(
            long reservationHoldId,
            long capacityBucketId,
            int occupiedPeople,
            long capacityPolicyVersion
    ) {
        this.reservationHoldId = requirePositive(reservationHoldId, "reservationHoldId");
        this.capacityBucketId = requirePositive(capacityBucketId, "capacityBucketId");
        this.occupiedPeople = requirePositive(occupiedPeople, "occupiedPeople");
        this.occupiedTeams = OCCUPIED_TEAMS_PER_HOLD;
        this.capacityPolicyVersion = requirePositive(
                capacityPolicyVersion,
                "capacityPolicyVersion"
        );
    }

    /** 선점 한 건이 버킷 하나에서 점유한 인원과 팀 1건을 기록한다. */
    public static ReservationHoldCapacityAllocation allocate(
            long reservationHoldId,
            long capacityBucketId,
            int occupiedPeople,
            long capacityPolicyVersion
    ) {
        return new ReservationHoldCapacityAllocation(
                reservationHoldId,
                capacityBucketId,
                occupiedPeople,
                capacityPolicyVersion
        );
    }

    private static long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public Long getReservationHoldId() {
        return reservationHoldId;
    }

    public Long getCapacityBucketId() {
        return capacityBucketId;
    }

    public int getOccupiedPeople() {
        return occupiedPeople;
    }

    public int getOccupiedTeams() {
        return occupiedTeams;
    }

    public long getCapacityPolicyVersion() {
        return capacityPolicyVersion;
    }
}
