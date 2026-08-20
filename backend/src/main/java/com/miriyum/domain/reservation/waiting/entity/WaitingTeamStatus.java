package com.miriyum.domain.reservation.waiting.entity;

/** 웨이팅 원장의 공개 팀 상태다. */
public enum WaitingTeamStatus {
    WAITING,
    CALLED,
    ARRIVED,
    CHECKED_IN,
    CANCELLED,
    NO_SHOW,
    CLOSED_BY_STORE,
    RESERVATION_CONVERTING,
    RESERVATION_CONVERTED
}
