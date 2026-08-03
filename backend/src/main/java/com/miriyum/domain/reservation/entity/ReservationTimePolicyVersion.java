package com.miriyum.domain.reservation.entity;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.entity.BaseEntity;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 매장별 슬롯 간격과 서비스·전환 duration을 버전으로 보존한다.
 */
@Entity
@Table(name = "reservation_time_policy_versions")
public class ReservationTimePolicyVersion extends BaseEntity {

    private static final int MAX_DURATION_MINUTES = 1440;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_time_policy_version_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "version_number", nullable = false)
    private long versionNumber;

    @Column(name = "slot_interval_minutes", nullable = false)
    private int slotIntervalMinutes;

    @Column(name = "service_duration_minutes", nullable = false)
    private int serviceDurationMinutes;

    @Column(name = "turnover_duration_minutes", nullable = false)
    private int turnoverDurationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ReservationTimePolicyStatus status;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    protected ReservationTimePolicyVersion() {
    }

    private ReservationTimePolicyVersion(
            long storeId,
            long versionNumber,
            int slotIntervalMinutes,
            int serviceDurationMinutes,
            int turnoverDurationMinutes
    ) {
        this.storeId = requirePositive(storeId, "storeId");
        this.versionNumber = requirePositive(versionNumber, "versionNumber");
        this.slotIntervalMinutes = requireRange(
                slotIntervalMinutes,
                1,
                MAX_DURATION_MINUTES,
                "slotIntervalMinutes"
        );
        this.serviceDurationMinutes = requireRange(
                serviceDurationMinutes,
                1,
                MAX_DURATION_MINUTES,
                "serviceDurationMinutes"
        );
        this.turnoverDurationMinutes = requireRange(
                turnoverDurationMinutes,
                0,
                MAX_DURATION_MINUTES,
                "turnoverDurationMinutes"
        );
        if (this.serviceDurationMinutes + this.turnoverDurationMinutes
                > MAX_DURATION_MINUTES) {
            throw new IllegalArgumentException(
                    "serviceDurationMinutes and turnoverDurationMinutes exceed 1440"
            );
        }
        this.status = ReservationTimePolicyStatus.DRAFT;
    }

    /**
     * 새 시간 정책 초안을 만든다.
     *
     * @param storeId 정책 소유 매장 ID
     * @param versionNumber 매장 안에서 증가하는 정책 버전
     * @param slotIntervalMinutes 예약 시작 간격(분)
     * @param serviceDurationMinutes 고객 서비스 duration(분)
     * @param turnoverDurationMinutes 서비스 뒤 전환 duration(분)
     * @return 게시 전 초안
     */
    public static ReservationTimePolicyVersion createDraft(
            long storeId,
            long versionNumber,
            int slotIntervalMinutes,
            int serviceDurationMinutes,
            int turnoverDurationMinutes
    ) {
        return new ReservationTimePolicyVersion(
                storeId,
                versionNumber,
                slotIntervalMinutes,
                serviceDurationMinutes,
                turnoverDurationMinutes
        );
    }

    /**
     * 초안을 미래 효력 시각의 게시 예약 상태로 전환한다.
     *
     * @param scheduledAt 정책 효력이 시작될 중앙 시각
     * @param now 명령을 판정하는 중앙 시각
     * @throws IllegalArgumentException 시각이 없거나 효력 시각이 미래가 아닌 경우
     * @throws ServiceException 현재 상태가 초안이 아닌 경우
     */
    public void schedule(Instant scheduledAt, Instant now) {
        requireStatus(ReservationTimePolicyStatus.DRAFT);
        Instant validatedScheduledAt = requireNonNull(scheduledAt, "scheduledAt");
        Instant validatedNow = requireNonNull(now, "now");
        if (!validatedScheduledAt.isAfter(validatedNow)) {
            throw new IllegalArgumentException("scheduledAt must be after now");
        }
        status = ReservationTimePolicyStatus.SCHEDULED;
        effectiveAt = validatedScheduledAt;
    }

    /**
     * 초안은 즉시, 게시 예약은 효력 시각 이후 활성 정책으로 전환한다.
     *
     * @param activationAt 실제 활성화 중앙 시각
     * @throws IllegalArgumentException 시각이 없거나 예약 효력 시각보다 이른 경우
     * @throws ServiceException 초안 또는 게시 예약 상태가 아닌 경우
     */
    public void activate(Instant activationAt) {
        Instant validatedActivationAt = requireNonNull(activationAt, "activationAt");
        if (status == ReservationTimePolicyStatus.SCHEDULED
                && validatedActivationAt.isBefore(effectiveAt)) {
            throw new IllegalArgumentException("activationAt must not be before effectiveAt");
        }
        if (status != ReservationTimePolicyStatus.DRAFT
                && status != ReservationTimePolicyStatus.SCHEDULED) {
            throw invalidTransition();
        }
        if (status == ReservationTimePolicyStatus.DRAFT) {
            effectiveAt = validatedActivationAt;
        }
        status = ReservationTimePolicyStatus.ACTIVE;
        activatedAt = validatedActivationAt;
    }

    /**
     * 효력 전 게시 예약을 초안으로 되돌린다.
     *
     * @param now 철회 명령을 판정하는 중앙 시각
     * @throws IllegalArgumentException 시각이 없거나 이미 효력 경계에 도달한 경우
     * @throws ServiceException 현재 상태가 게시 예약이 아닌 경우
     */
    public void cancelPublication(Instant now) {
        requireStatus(ReservationTimePolicyStatus.SCHEDULED);
        Instant validatedNow = requireNonNull(now, "now");
        if (!validatedNow.isBefore(effectiveAt)) {
            throw new IllegalArgumentException("publication is already effective");
        }
        status = ReservationTimePolicyStatus.DRAFT;
        effectiveAt = null;
        activatedAt = null;
    }

    /**
     * 활성 정책을 새 정책으로 대체 가능한 퇴역 상태로 전환한다.
     */
    public void retire() {
        requireStatus(ReservationTimePolicyStatus.ACTIVE);
        status = ReservationTimePolicyStatus.RETIRED;
    }

    /**
     * 게시 예약 정책의 활성화 실패를 종결 상태로 기록한다.
     */
    public void failActivation() {
        requireStatus(ReservationTimePolicyStatus.SCHEDULED);
        status = ReservationTimePolicyStatus.ACTIVATION_FAILED;
    }

    private void requireStatus(ReservationTimePolicyStatus expected) {
        if (status != expected) {
            throw invalidTransition();
        }
    }

    private static ServiceException invalidTransition() {
        return new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION);
    }

    private static long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static int requireRange(int value, int minimum, int maximum, String fieldName) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    fieldName + " must be between " + minimum + " and " + maximum
            );
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

    public long getVersionNumber() {
        return versionNumber;
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

    public ReservationTimePolicyStatus getStatus() {
        return status;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }
}
