package com.miriyum.domain.reservation.waiting.entity;

/**
 * 웨이팅 기능 비활성화 시 이미 활성인 팀을 처리하는 정책이다.
 */
public enum WaitingDisableAction {
    /** 기존 활성 팀을 그대로 유지한다. */
    KEEP_ACTIVE,
    /** #272 비동기 closure job으로 활성 팀 종결을 요청한다. */
    CLOSE_ACTIVE_TEAMS
}
