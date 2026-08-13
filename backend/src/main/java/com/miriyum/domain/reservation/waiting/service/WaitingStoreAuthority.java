package com.miriyum.domain.reservation.waiting.service;

import java.time.ZoneId;

/** 권한 확인이 끝난 매장의 Waiting 시간 기준이다. */
public record WaitingStoreAuthority(long storeId, ZoneId timeZoneId) {

    public WaitingStoreAuthority {
        if (storeId <= 0 || timeZoneId == null) {
            throw new IllegalArgumentException("store authority fields must be valid");
        }
    }
}
