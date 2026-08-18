package com.miriyum.domain.reservation.waiting.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

/** 소비자 웨이팅 등록 입력이다. 영업일은 직전 availability 응답 값을 사용한다. */
public record WaitingConsumerCreateRequest(
        @NotNull LocalDate businessDate,
        @Positive int partySize
) {
}
