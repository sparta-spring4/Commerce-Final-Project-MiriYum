package com.miriyum.domain.reservation.waiting.entity;

/** 활성 팀 종결 작업의 공개 상태다. */
public enum WaitingClosureJobStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    RECONCILIATION_REQUIRED
}
