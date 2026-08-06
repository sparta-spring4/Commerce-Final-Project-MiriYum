package com.miriyum.domain.menuhold.inventory.dto;

/** 실제 잠금·차감에 성공한 버킷과 메뉴 선택의 내부 대응 결과다. */
public record InventoryAcquisitionResult(
        long inventoryBucketId,
        long menuId,
        long inventoryPolicyVersion,
        int quantity,
        int onlineHoldQuantity,
        int sharedQuantity
) {
}
