package com.miriyum.domain.reservation.waiting.entity;

/** 종결 작업에 고정된 개별 팀의 처리 상태다. */
public enum WaitingClosureItemStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    RECONCILIATION_REQUIRED
}
