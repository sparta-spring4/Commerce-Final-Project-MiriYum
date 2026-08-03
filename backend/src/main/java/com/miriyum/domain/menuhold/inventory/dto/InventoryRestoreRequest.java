package com.miriyum.domain.menuhold.inventory.dto;

public record InventoryRestoreRequest(
        String commandId,
        String acquireCommandId
) {
    public InventoryRestoreRequest {
        if (commandId == null || commandId.isBlank() || commandId.length() > 100
                || acquireCommandId == null || acquireCommandId.isBlank()
                || acquireCommandId.length() > 100) {
            throw new IllegalArgumentException("inventory restore request is required");
        }
    }
}
