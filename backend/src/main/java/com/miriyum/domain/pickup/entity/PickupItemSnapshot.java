package com.miriyum.domain.pickup.entity;

import java.time.LocalDate;
import java.time.LocalTime;

/** 픽업 생성 시점의 메뉴 표시 정보와 실제 확보 재고 버킷을 보존한다. */
public record PickupItemSnapshot(
        long menuId,
        long menuInventoryBucketId,
        long menuPolicyVersion,
        String menuName,
        int unitPrice,
        long inventoryPolicyVersion,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime,
        int quantity
) {

    public PickupItemSnapshot {
        if (menuId <= 0 || menuInventoryBucketId <= 0
                || menuPolicyVersion <= 0 || inventoryPolicyVersion <= 0
                || menuName == null || menuName.isBlank() || menuName.length() > 100
                || unitPrice < 0 || serviceDate == null || startTime == null
                || endDate == null || endTime == null
                || !endDate.atTime(endTime).isAfter(serviceDate.atTime(startTime))
                || quantity <= 0) {
            throw new IllegalArgumentException("invalid pickup item snapshot");
        }
    }
}
