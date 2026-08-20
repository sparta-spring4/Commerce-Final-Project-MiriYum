package com.miriyum.domain.reservation.waiting.dto;

/** 최초 실행과 재생에 공통인 웨이팅 팀 명령 결과다. */
public record WaitingCommandResult(int httpStatus, WaitingTeamSnapshot data) {
}
