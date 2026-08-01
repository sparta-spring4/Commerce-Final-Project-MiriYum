package com.miriyum.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 예약이 특정 수용량 버킷에서 실제 점유한 인원과 팀 수의 이력이다.
 */
@Entity
@Table(name = "reservation_capacity_allocations")
public class ReservationCapacityAllocation {

    private static final int OCCUPIED_TEAMS_PER_RESERVATION = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_capacity_allocation_id")
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "reservation_capacity_bucket_id", nullable = false)
    private Long capacityBucketId;

    @Column(name = "occupied_people", nullable = false)
    private int occupiedPeople;

    @Column(name = "occupied_teams", nullable = false)
    private int occupiedTeams;

    @Column(name = "capacity_policy_version", nullable = false)
    private long capacityPolicyVersion;

    protected ReservationCapacityAllocation() {
    }

    private ReservationCapacityAllocation(
            Long reservationId,
            Long capacityBucketId,
            int occupiedPeople,
            long capacityPolicyVersion
    ) {
        this.reservationId = requirePositive(reservationId, "reservationId");
        this.capacityBucketId = requirePositive(capacityBucketId, "capacityBucketId");
        this.occupiedPeople = requirePositive(occupiedPeople, "occupiedPeople");
        this.occupiedTeams = OCCUPIED_TEAMS_PER_RESERVATION;
        this.capacityPolicyVersion = requirePositive(
                capacityPolicyVersion,
                "capacityPolicyVersion"
        );
    }

    /**
     * 예약 한 건이 버킷 하나에서 점유한 인원과 팀 1건의 이력을 만든다.
     *
     * @param reservationId 예약 ID
     * @param capacityBucketId 수용량 버킷 ID
     * @param occupiedPeople 실제 점유 인원
     * @param capacityPolicyVersion 적용 수용량 정책 버전
     * @return 변경할 수 없는 수용량 배정 스냅샷
     * @throws IllegalArgumentException ID, 점유 인원, 정책 버전이 양수가 아닌 경우
     */
    public static ReservationCapacityAllocation allocate(
            Long reservationId,
            Long capacityBucketId,
            int occupiedPeople,
            long capacityPolicyVersion
    ) {
        return new ReservationCapacityAllocation(
                reservationId,
                capacityBucketId,
                occupiedPeople,
                capacityPolicyVersion
        );
    }

    private static Long requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
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

    private static long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public Long getReservationId() {
        return reservationId;
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
