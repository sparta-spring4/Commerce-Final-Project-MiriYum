package com.miriyum.domain.pickup.entity;

/** 픽업 생성 시점의 메뉴 표시 정보와 실제 확보 재고 버킷을 보존한다. */
public record PickupItemSnapshot(
        long menuId,
        long menuInventoryBucketId,
        long menuPolicyVersion,
        String menuName,
        int unitPrice,
        long inventoryPolicyVersion,
        int quantity
) {

    public PickupItemSnapshot {
        if (menuId <= 0 || menuInventoryBucketId <= 0
                || menuPolicyVersion <= 0 || inventoryPolicyVersion <= 0
                || menuName == null || menuName.isBlank() || menuName.length() > 100
                || unitPrice < 0 || quantity <= 0) {
            throw new IllegalArgumentException("invalid pickup item snapshot");
        }
    }
}
