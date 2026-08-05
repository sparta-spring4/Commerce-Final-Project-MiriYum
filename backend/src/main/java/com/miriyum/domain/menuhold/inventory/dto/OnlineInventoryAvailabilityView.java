package com.miriyum.domain.menuhold.inventory.dto;

import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import java.time.LocalDate;
import java.time.LocalTime;

/** 현재 정책 버킷의 온라인 가용량 계산에 필요한 내부 조회 projection이다. */
public interface OnlineInventoryAvailabilityView {
    long getMenuId();

    long getInventoryPolicyVersion();

    String getTimeZoneId();

    LocalDate getServiceDate();

    LocalTime getStartTime();

    LocalDate getEndDate();

    LocalTime getEndTime();

    int getOnlineHoldRemaining();

    int getSharedRemaining();

    boolean isSharedOnlineAllowed();

    InventoryAvailabilityStatus getAvailabilityStatus();
}
