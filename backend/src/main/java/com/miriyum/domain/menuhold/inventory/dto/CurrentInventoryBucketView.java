package com.miriyum.domain.menuhold.inventory.dto;

import java.time.LocalDate;
import java.time.LocalTime;

public interface CurrentInventoryBucketView {
    long getMenuId();
    long getBucketId();
    long getInventoryPolicyVersion();
    String getTimeZoneId();
    LocalDate getServiceDate();
    LocalTime getStartTime();
    LocalDate getEndDate();
    LocalTime getEndTime();
}
