package com.miriyum.domain.menuhold.dto;

import java.util.List;

/** 픽업 소비자에게 내부 풀 배분을 노출하지 않는 확보 결과다. */
public record MenuInventoryAcquireResult(
        String operationId,
        List<MenuInventoryAcquiredItem> items
) {
    public MenuInventoryAcquireResult {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        items = List.copyOf(items);
    }
}
