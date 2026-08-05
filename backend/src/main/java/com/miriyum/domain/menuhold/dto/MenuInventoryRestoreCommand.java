package com.miriyum.domain.menuhold.dto;

/** 최초 확보 원장의 실제 풀 배분을 복구하는 공개 명령이다. */
public record MenuInventoryRestoreCommand(
        String operationId,
        String sourceAcquireOperationId
) {
    public MenuInventoryRestoreCommand {
        requireText(operationId, "operationId");
        requireText(sourceAcquireOperationId, "sourceAcquireOperationId");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
