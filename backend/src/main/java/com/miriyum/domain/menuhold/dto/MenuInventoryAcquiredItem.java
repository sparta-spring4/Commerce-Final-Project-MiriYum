package com.miriyum.domain.menuhold.dto;

/**
 * 실제 차감한 재고 버킷을 식별하되 내부 풀 배분은 숨긴 메뉴별 확보 완료 항목이다.
 *
 * @param inventoryBucketId 실제 잠금·조건부 차감에 성공한 현재 정책 버킷 ID
 * @param menuId 확보한 메뉴 ID
 * @param inventoryPolicyVersion 확보한 재고 정책 버전
 * @param quantity 확보 수량
 */
public record MenuInventoryAcquiredItem(
        long inventoryBucketId,
        long menuId,
        long inventoryPolicyVersion,
        int quantity
) {
    public MenuInventoryAcquiredItem {
        if (inventoryBucketId <= 0 || menuId <= 0
                || inventoryPolicyVersion <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("acquired item values must be positive");
        }
    }
}
