package com.miriyum.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.List;

/**
 * 예약 당시 시간 정책과 실제 서비스·점유 구간을 재현하는 불변 스냅샷이다.
 */
@Embeddable
public class ReservationTimeSnapshot {

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "service_end_at")
    private Instant serviceEndAt;

    @Column(name = "occupancy_end_at")
    private Instant occupancyEndAt;

    @Column(name = "time_zone_id_snapshot", length = 64)
    private String timeZoneId;

    @Column(name = "start_offset_seconds")
    private Integer startOffsetSeconds;

    @Column(name = "service_end_offset_seconds")
    private Integer serviceEndOffsetSeconds;

    @Column(name = "occupancy_end_offset_seconds")
    private Integer occupancyEndOffsetSeconds;

    @Column(name = "slot_interval_minutes")
    private Integer slotIntervalMinutes;

    @Column(name = "service_duration_minutes")
    private Integer serviceDurationMinutes;

    @Column(name = "turnover_duration_minutes")
    private Integer turnoverDurationMinutes;

    @Column(name = "reservation_time_policy_store_id")
    private Long reservationTimePolicyStoreId;

    @Column(name = "reservation_policy_version", nullable = false)
    private long reservationTimePolicyVersion;

    protected ReservationTimeSnapshot() {
    }

    private ReservationTimeSnapshot(
            LocalDate serviceDate,
            Instant startAt,
            Instant serviceEndAt,
            Instant occupancyEndAt,
            ZoneId timeZone,
            ZoneOffset startOffset,
            ZoneOffset serviceEndOffset,
            ZoneOffset occupancyEndOffset,
            ReservationTimePolicyVersion policy
    ) {
        this.serviceDate = serviceDate;
        this.startAt = startAt;
        this.serviceEndAt = serviceEndAt;
        this.occupancyEndAt = occupancyEndAt;
        this.timeZoneId = timeZone.getId();
        this.startOffsetSeconds = startOffset.getTotalSeconds();
        this.serviceEndOffsetSeconds = serviceEndOffset.getTotalSeconds();
        this.occupancyEndOffsetSeconds = occupancyEndOffset.getTotalSeconds();
        this.slotIntervalMinutes = policy.getSlotIntervalMinutes();
        this.serviceDurationMinutes = policy.getServiceDurationMinutes();
        this.turnoverDurationMinutes = policy.getTurnoverDurationMinutes();
        this.reservationTimePolicyStoreId = policy.getStoreId();
        this.reservationTimePolicyVersion = policy.getVersionNumber();
    }

    /**
     * 매장 현지 시작 시각을 명확한 Instant로 해석하고 정책 duration을 합산한다.
     *
     * <p>존재하지 않는 DST 시각은 거부한다. 중복 시각은 호출자가 해당 시간대에서
     * 유효한 offset을 명시한 경우에만 허용한다.</p>
     *
     * @param policy 계산에 적용할 활성 Reservation 시간 정책
     * @param localStartAt 매장 현지 시작 날짜·시각
     * @param timeZone 매장 IANA 시간대
     * @param requestedOffset 중복 시각을 식별할 offset 또는 일반 시각의 검증용 offset
     * @return 실제 날짜와 offset을 보존한 시간 스냅샷
     * @throws IllegalArgumentException 입력이 없거나 정책이 비활성이거나 현지 시각을 명확히 해석할 수 없는 경우
     */
    public static ReservationTimeSnapshot calculate(
            ReservationTimePolicyVersion policy,
            LocalDateTime localStartAt,
            ZoneId timeZone,
            ZoneOffset requestedOffset
    ) {
        if (policy == null || policy.getStatus() != ReservationTimePolicyStatus.ACTIVE) {
            throw new IllegalArgumentException("active reservation time policy is required");
        }
        if (localStartAt == null || timeZone == null) {
            throw new IllegalArgumentException("localStartAt and timeZone are required");
        }
        if (localStartAt.getSecond() != 0 || localStartAt.getNano() != 0) {
            throw new IllegalArgumentException("localStartAt must use minute precision");
        }

        ZoneRules rules = timeZone.getRules();
        ZoneOffset startOffset = resolveOffset(rules, localStartAt, requestedOffset);
        Instant startAt = localStartAt.toInstant(startOffset);
        Instant serviceEndAt = startAt.plus(
                Duration.ofMinutes(policy.getServiceDurationMinutes())
        );
        Instant occupancyEndAt = serviceEndAt.plus(
                Duration.ofMinutes(policy.getTurnoverDurationMinutes())
        );

        return new ReservationTimeSnapshot(
                localStartAt.toLocalDate(),
                startAt,
                serviceEndAt,
                occupancyEndAt,
                timeZone,
                startOffset,
                rules.getOffset(serviceEndAt),
                rules.getOffset(occupancyEndAt),
                policy
        );
    }

    private static ZoneOffset resolveOffset(
            ZoneRules rules,
            LocalDateTime localStartAt,
            ZoneOffset requestedOffset
    ) {
        List<ZoneOffset> validOffsets = rules.getValidOffsets(localStartAt);
        if (validOffsets.isEmpty()) {
            throw new IllegalArgumentException("localStartAt does not exist in timeZone");
        }
        if (validOffsets.size() > 1 && requestedOffset == null) {
            throw new IllegalArgumentException("ambiguous localStartAt requires offset");
        }
        if (requestedOffset != null) {
            if (!validOffsets.contains(requestedOffset)) {
                throw new IllegalArgumentException("requestedOffset does not match timeZone");
            }
            return requestedOffset;
        }
        return validOffsets.getFirst();
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getServiceEndAt() {
        return serviceEndAt;
    }

    public Instant getOccupancyEndAt() {
        return occupancyEndAt;
    }

    public String getTimeZoneId() {
        return timeZoneId;
    }

    /**
     * V18 신규 예약처럼 실제 시각과 계산 근거가 모두 보존됐는지 확인한다.
     */
    public boolean hasResolvedTime() {
        return serviceDate != null
                && startAt != null
                && serviceEndAt != null
                && occupancyEndAt != null
                && timeZoneId != null
                && !timeZoneId.isBlank()
                && startOffsetSeconds != null
                && serviceEndOffsetSeconds != null
                && occupancyEndOffsetSeconds != null
                && slotIntervalMinutes != null
                && serviceDurationMinutes != null
                && turnoverDurationMinutes != null
                && reservationTimePolicyStoreId != null
                && reservationTimePolicyVersion > 0;
    }

    public int getStartOffsetSeconds() {
        return startOffsetSeconds;
    }

    public int getServiceEndOffsetSeconds() {
        return serviceEndOffsetSeconds;
    }

    public int getOccupancyEndOffsetSeconds() {
        return occupancyEndOffsetSeconds;
    }

    public int getSlotIntervalMinutes() {
        return slotIntervalMinutes;
    }

    public int getServiceDurationMinutes() {
        return serviceDurationMinutes;
    }

    public int getTurnoverDurationMinutes() {
        return turnoverDurationMinutes;
    }

    public Long getReservationTimePolicyStoreId() {
        return reservationTimePolicyStoreId;
    }

    boolean belongsToStore(long storeId) {
        return reservationTimePolicyStoreId != null
                && reservationTimePolicyStoreId == storeId;
    }

    public long getReservationTimePolicyVersion() {
        return reservationTimePolicyVersion;
    }
}
