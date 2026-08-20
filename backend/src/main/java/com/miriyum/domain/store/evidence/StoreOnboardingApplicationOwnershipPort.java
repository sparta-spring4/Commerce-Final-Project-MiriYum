package com.miriyum.domain.store.evidence;

/**
 * 입점 신청과 매장 운영자 계정의 소유 관계를 확인하는 #277 연계 계약이다.
 *
 * <p>증빙 원장은 신청 aggregate를 직접 조회하지 않는다. #277이 권위 있는 신청 상태를 소유한 뒤 이 port를
 * 구현해, 증빙 연결 전에 신청·자료 version·운영자 계정의 일치를 확인한다.</p>
 */
public interface StoreOnboardingApplicationOwnershipPort {

    void requireOwnership(long onboardingApplicationId, long applicationVersion, long storeOperatorAccountId);
}
