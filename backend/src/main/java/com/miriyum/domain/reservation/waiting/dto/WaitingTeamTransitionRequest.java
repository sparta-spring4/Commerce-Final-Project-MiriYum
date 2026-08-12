package com.miriyum.domain.reservation.waiting.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotNull;

/** 운영자가 마지막으로 조회한 웨이팅 팀 버전을 전달하는 전이 요청이다. */
public record WaitingTeamTransitionRequest(
        @NotNull @PositiveOrZero
        Long expectedVersion
) {
}
