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
 * 일반 방문 예약의 거래 스냅샷과 승인된 종결 전이를 소유한다.
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
     * 승인된 소유 관계와 거래 스냅샷으로 즉시 확정 예약을 만든다.
     *
     * @param consumerAccountId 예약 대표자 계정 ID
     * @param storeId 대상 매장 ID
     * @param storeNameSnapshot 예약 당시 매장 표시명
     * @param serviceDate 매장 업무 날짜
     * @param startTime 방문 시작 시각
     * @param endTime 점유 종료 시각
     * @param party 예약 당시 인원 구성
     * @param capacityPolicyVersion 적용 수용량 정책 버전
     * @param reservationPolicyVersion 적용 예약 정책 버전
     * @param createdAt 예약 확정 시각
     * @return 즉시 확정된 예약
     * @throws IllegalArgumentException 필수 값이 없거나 ID·정책 버전·매장명이 유효하지 않은 경우
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
     * 확정 예약을 취소 상태로 종결한다.
     *
     * @param cancelledAt 취소 확정 시각
     * @throws IllegalArgumentException 취소 확정 시각이 없는 경우
     * @throws ServiceException 현재 상태가 확정이 아닌 경우
     */
    public void cancel(Instant cancelledAt) {
        requireConfirmed();
        this.cancelledAt = requireNonNull(cancelledAt, "cancelledAt");
        this.status = ReservationStatus.CANCELLED;
    }

    /**
     * 확정 예약을 방문 완료 상태로 종결한다.
     *
     * @param fulfilledAt 방문 완료 확정 시각
     * @throws IllegalArgumentException 방문 완료 확정 시각이 없는 경우
     * @throws ServiceException 현재 상태가 확정이 아닌 경우
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
