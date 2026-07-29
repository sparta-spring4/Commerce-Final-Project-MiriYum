package com.miriyum.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 매장·업무 날짜·시간 구간별 예약 인원과 팀 수 정책 스냅샷이다.
 */
@Entity
@Table(name = "reservation_capacity_buckets")
public class ReservationCapacityBucket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_capacity_bucket_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "max_people", nullable = false)
    private int maxPeople;

    @Column(name = "max_teams", nullable = false)
    private int maxTeams;

    @Column(name = "occupied_people", nullable = false)
    private int occupiedPeople;

    @Column(name = "occupied_teams", nullable = false)
    private int occupiedTeams;

    @Column(name = "min_party_size", nullable = false)
    private int minPartySize;

    @Column(name = "max_party_size", nullable = false)
    private int maxPartySize;

    @Column(name = "infants_allowed", nullable = false)
    private boolean infantsAllowed;

    @Column(name = "policy_version", nullable = false)
    private long policyVersion;

    protected ReservationCapacityBucket() {
    }

    private ReservationCapacityBucket(
            Long storeId,
            LocalDate serviceDate,
            LocalTime startTime,
            LocalTime endTime,
            int maxPeople,
            int maxTeams,
            int occupiedPeople,
            int occupiedTeams,
            int minPartySize,
            int maxPartySize,
            boolean infantsAllowed,
            long policyVersion
    ) {
        this.storeId = requirePositive(storeId, "storeId");
        this.serviceDate = requireNonNull(serviceDate, "serviceDate");
        this.startTime = requireNonNull(startTime, "startTime");
        this.endTime = requireNonNull(endTime, "endTime");
        this.maxPeople = requireNonNegative(maxPeople, "maxPeople");
        this.maxTeams = requireNonNegative(maxTeams, "maxTeams");
        this.occupiedPeople = requireNonNegative(occupiedPeople, "occupiedPeople");
        this.occupiedTeams = requireNonNegative(occupiedTeams, "occupiedTeams");
        this.minPartySize = requirePositive(minPartySize, "minPartySize");
        this.maxPartySize = requirePositive(maxPartySize, "maxPartySize");
        if (maxPartySize < minPartySize) {
            throw new IllegalArgumentException(
                    "maxPartySize must be greater than or equal to minPartySize"
            );
        }
        if (maxPartySize > maxPeople) {
            throw new IllegalArgumentException(
                    "maxPartySize must be less than or equal to maxPeople"
            );
        }
        this.infantsAllowed = infantsAllowed;
        this.policyVersion = requirePositive(policyVersion, "policyVersion");
    }

    /**
     * 게시가 승인된 구간별 수용량 정책과 현재 점유 스냅샷을 만든다.
     *
     * @param storeId 대상 매장 ID
     * @param serviceDate 매장 업무 날짜
     * @param startTime 구간 시작 시각
     * @param endTime 구간 종료 시각
     * @param maxPeople 최대 예약 인원
     * @param maxTeams 최대 예약 팀 수
     * @param occupiedPeople 현재 점유 인원
     * @param occupiedTeams 현재 점유 팀 수
     * @param minPartySize 최소 일행 인원
     * @param maxPartySize 최대 일행 인원
     * @param infantsAllowed 영유아 동반 허용 여부
     * @param policyVersion 적용 수용량 정책 버전
     * @return 검증된 수용량 버킷 스냅샷
     * @throws IllegalArgumentException 필수 값이나 구조적 수용량 불변식이 유효하지 않은 경우
     */
    public static ReservationCapacityBucket create(
            Long storeId,
            LocalDate serviceDate,
            LocalTime startTime,
            LocalTime endTime,
            int maxPeople,
            int maxTeams,
            int occupiedPeople,
            int occupiedTeams,
            int minPartySize,
            int maxPartySize,
            boolean infantsAllowed,
            long policyVersion
    ) {
        return new ReservationCapacityBucket(
                storeId,
                serviceDate,
                startTime,
                endTime,
                maxPeople,
                maxTeams,
                occupiedPeople,
                occupiedTeams,
                minPartySize,
                maxPartySize,
                infantsAllowed,
                policyVersion
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

    private static int requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public Long getStoreId() {
        return storeId;
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

    public int getMaxPeople() {
        return maxPeople;
    }

    public int getMaxTeams() {
        return maxTeams;
    }

    public int getOccupiedPeople() {
        return occupiedPeople;
    }

    public int getOccupiedTeams() {
        return occupiedTeams;
    }

    public int getMinPartySize() {
        return minPartySize;
    }

    public int getMaxPartySize() {
        return maxPartySize;
    }

    public boolean isInfantsAllowed() {
        return infantsAllowed;
    }

    public long getPolicyVersion() {
        return policyVersion;
    }
}
