package com.miriyum.domain.menuhold.dto;

/** 내부 버킷·풀 배분을 숨긴 메뉴별 확보 완료 항목이다. */
public record MenuInventoryAcquiredItem(
        long menuId,
        long inventoryPolicyVersion,
        int quantity
) {
    public MenuInventoryAcquiredItem {
        if (menuId <= 0 || inventoryPolicyVersion <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("acquired item values must be positive");
        }
    }
}
