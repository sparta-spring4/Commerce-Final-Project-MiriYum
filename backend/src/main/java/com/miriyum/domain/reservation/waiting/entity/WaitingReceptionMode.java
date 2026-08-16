package com.miriyum.domain.reservation.waiting.entity;

/**
 * 매장의 신규 웨이팅 접수 방식이다.
 */
public enum WaitingReceptionMode {
    /** 영업 구간과 사전 오픈 설정을 따르는 자동 접수다. */
    AUTO,
    /** 운영자가 명시적으로 여는 수동 접수다. */
    MANUAL,
    /** 기존 팀은 유지하고 신규 접수만 일시중지한다. */
    PAUSED
}
