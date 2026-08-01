package com.miriyum.domain.reservation.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

/**
 * 날짜별 전체 게시에 포함되는 예약 수용량 구간 하나다.
 *
 * @param startTime 구간 시작 시각
 * @param endTime 구간 종료 시각
 * @param maxPeople 최대 예약 인원
 * @param maxTeams 최대 예약 팀 수
 * @param minPartySize 최소 일행 인원
 * @param maxPartySize 최대 일행 인원
 * @param infantsAllowed 영유아 동반 허용 여부
 */
public record CapacityBucketRequest(
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotNull @Min(0) @Max(10_000) Integer maxPeople,
        @NotNull @Min(0) @Max(10_000) Integer maxTeams,
        @Min(1) @Max(100) int minPartySize,
        @Min(1) @Max(100) int maxPartySize,
        @NotNull Boolean infantsAllowed
) {
}
