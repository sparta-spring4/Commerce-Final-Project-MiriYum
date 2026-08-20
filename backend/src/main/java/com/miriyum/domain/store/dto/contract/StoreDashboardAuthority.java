package com.miriyum.domain.store.dto.contract;

/**
 * 대시보드 통계 소비자가 매장 소유권과 집계 경계를 확인하는 공개 계약이다.
 *
 * @param storeId 매장 식별자
 * @param timeZoneId 검증된 IANA 시간대 식별자
 * @param dashboardAuthorityVersion 매장 권한 스냅샷 버전
 */
public record StoreDashboardAuthority(
        long storeId,
        String timeZoneId,
        long dashboardAuthorityVersion
) {
    public StoreDashboardAuthority {
        if (storeId <= 0
                || dashboardAuthorityVersion <= 0
                || !"Asia/Seoul".equals(timeZoneId)) {
            throw new IllegalArgumentException("valid KST dashboard authority is required");
        }
    }
}
