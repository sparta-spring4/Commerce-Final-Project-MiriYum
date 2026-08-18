package com.miriyum.domain.reservation.waiting.dto;

/** 최초 실행과 재생에 공통인 소비자 웨이팅 명령 결과다. */
public record WaitingConsumerCommandResult(
        int httpStatus,
        WaitingConsumerSnapshot data
) {
}
