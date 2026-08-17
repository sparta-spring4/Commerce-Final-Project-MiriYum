package com.miriyum.domain.reservation.waiting.entity;

public enum WaitingAutoOpenJobStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    COMPLETED,
    INVALIDATED,
    RECONCILIATION_REQUIRED
}
