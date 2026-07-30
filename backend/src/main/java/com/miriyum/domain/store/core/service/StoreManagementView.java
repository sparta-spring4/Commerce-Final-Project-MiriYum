package com.miriyum.domain.store.core.service;

import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.PickupEligibility;
import com.miriyum.domain.store.core.enums.VerificationStatus;

public record StoreManagementView(
        long storeId,
        OperationStatus operationStatus,
        VerificationStatus verificationStatus,
        PickupEligibility pickupEligibility
) {
}
