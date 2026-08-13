package com.miriyum.domain.reservation.dto;

import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

/** 수용량 임시 선점 생성·종결 service가 사용하는 내부 명령과 replay 결과 계약이다. */
public final class ReservationHoldContracts {

    private ReservationHoldContracts() {
    }

    /**
     * 인증된 소비자가 선택한 의미만 전달하는 임시 선점 생성 명령이다.
     *
     * <p>버킷·정책 버전·만료 시각·연락 대상은 service가 해석하므로 입력받지 않는다.</p>
     */
    public record CreateCommand(
            long consumerAccountId,
            long storeId,
            LocalDate serviceDate,
            LocalTime startTime,
            ZoneOffset startOffset,
            int adultCount,
            int childCount,
            int infantCount,
            String creationCommandId
    ) {
    }

    /**
     * 상위 서버 조정자가 검증한 목표 상태를 한 선점에 적용하는 내부 종결 명령이다.
     *
     * <p>operation ID는 서버가 발급하며 결제·금전 결과나 HTTP 형상을 포함하지 않는다.</p>
     */
    public record TransitionCommand(
            long reservationHoldId,
            ReservationHoldStatus targetStatus,
            String operationId,
            String actorType,
            Long actorId,
            Instant requestedAt
    ) {
    }

    /** Entity를 노출하지 않고 생성·종결 replay에 재사용하는 선점 결과 스냅샷이다. */
    public record Result(
            long reservationHoldId,
            ReservationHoldStatus status,
            long statusVersion,
            long consumerAccountId,
            long storeId,
            LocalDate serviceDate,
            Instant startAt,
            Instant serviceEndAt,
            Instant occupancyEndAt,
            String timeZoneId,
            int adultCount,
            int childCount,
            int infantCount,
            long reservationTimePolicyVersion,
            long capacityPolicyVersion,
            Long cancellationPolicyVersion,
            Instant createdAt,
            Instant expiresAt
    ) {
    }
}
