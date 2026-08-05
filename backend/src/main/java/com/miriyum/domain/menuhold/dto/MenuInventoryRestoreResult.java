package com.miriyum.domain.menuhold.dto;

/** 내부 원장 식별자를 숨긴 복구 명령 결과다. */
public record MenuInventoryRestoreResult(
        String operationId,
        String sourceAcquireOperationId
) {
    public MenuInventoryRestoreResult {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        if (sourceAcquireOperationId == null || sourceAcquireOperationId.isBlank()) {
            throw new IllegalArgumentException("sourceAcquireOperationId must not be blank");
        }
    }
}
