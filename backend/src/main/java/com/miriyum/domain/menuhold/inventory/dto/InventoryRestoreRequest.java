package com.miriyum.domain.menuhold.inventory.dto;

public record InventoryRestoreRequest(
        String operationId,
        String sourceAcquireOperationId
) {
    public InventoryRestoreRequest {
        if (operationId == null || operationId.isBlank() || operationId.length() > 100
                || sourceAcquireOperationId == null || sourceAcquireOperationId.isBlank()
                || sourceAcquireOperationId.length() > 100) {
            throw new IllegalArgumentException("inventory restore request is required");
        }
    }
}
