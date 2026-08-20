package com.miriyum.domain.reservation.waiting.service;

/** Waiting이 소비하는 매장 운영자 권한과 상태의 최소 공개 경계다. */
public interface WaitingStoreAuthorityPort {

    WaitingStoreAuthority requireRead(long operatorAccountId, long storeId);

    WaitingStoreAuthority requireMutation(long operatorAccountId, long storeId);
}
