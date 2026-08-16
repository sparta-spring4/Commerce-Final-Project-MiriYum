package com.miriyum.domain.store.dto.administration;

import com.miriyum.domain.store.enums.OperationStatus;
import java.util.Set;

/** Store 도메인이 플랫폼 관리 유스케이스에 공개하는 Entity 비노출 계약이다. */
public final class StoreAdministrationContracts {

    private StoreAdministrationContracts() {
    }

    public enum RestrictedFeature {
        RESERVATION,
        WAITING,
        MENU_HOLD,
        PICKUP,
        STORE_MANAGEMENT
    }

    public record EnforcementCommand(
            long storeId,
            long expectedEnforcementVersion,
            long sanctionId,
            OperationStatus operationStatus,
            boolean reservationEnabled,
            boolean menuHoldEnabled,
            boolean pickupEnabled,
            boolean waitingAllowed,
            boolean storeManagementAllowed,
            Set<RestrictedFeature> restrictedFeatures
    ) {
        public EnforcementCommand {
            if (storeId <= 0 || expectedEnforcementVersion < 0 || sanctionId <= 0
                    || operationStatus == null || restrictedFeatures == null) {
                throw new IllegalArgumentException("store enforcement command is invalid");
            }
            restrictedFeatures = Set.copyOf(restrictedFeatures);
        }
    }

    public record EnforcementResult(
            long storeId,
            long enforcementVersion,
            OperationStatus operationStatus,
            boolean reservationEnabled,
            boolean menuHoldEnabled,
            boolean pickupEnabled,
            boolean waitingAllowed,
            boolean storeManagementAllowed,
            Set<RestrictedFeature> restrictedFeatures
    ) {
        public EnforcementResult {
            restrictedFeatures = Set.copyOf(restrictedFeatures);
        }
    }
}
