package com.miriyum.domain.store.dto.administration;

import com.miriyum.domain.store.enums.OperationStatus;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.List;

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

    public enum PermanentClosureCause {
        PLATFORM_SANCTION
    }

    public record PermanentClosureCommand(
            long storeId,
            long expectedEnforcementVersion,
            long sanctionId,
            long approvalId,
            PermanentClosureCause cause,
            String policyVersion
    ) {
        public PermanentClosureCommand {
            if (storeId <= 0 || expectedEnforcementVersion < 0 || sanctionId <= 0
                    || approvalId <= 0 || cause == null || policyVersion == null
                    || policyVersion.isBlank() || policyVersion.length() > 50) {
                throw new IllegalArgumentException("permanent Store closure command is invalid");
            }
        }
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
                    || restrictedFeatures == null) {
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

    public record ReleaseCommand(long storeId, long sanctionId) {
        public ReleaseCommand {
            if (storeId <= 0 || sanctionId <= 0) {
                throw new IllegalArgumentException("store enforcement release is invalid");
            }
        }
    }

    public record StoreBaseSettings(
            OperationStatus operationStatus,
            Boolean reservationEnabled,
            Boolean menuHoldEnabled,
            Boolean pickupEnabled
    ) {
        public StoreBaseSettings {
            if (operationStatus == OperationStatus.CLOSED) {
                throw new IllegalArgumentException("operator Store base settings are invalid");
            }
        }
    }

    public record StoreSnapshot(long storeId, String name, long storeOperatorAccountId,
                                OperationStatus operationStatus, boolean reservationEnabled,
                                boolean menuHoldEnabled, boolean pickupEnabled,
                                long enforcementVersion, LocalDateTime createdAt) {}
    public record StoreSnapshotPage(List<StoreSnapshot> content, int page, int size,
                                    long totalElements, int totalPages) {
        public StoreSnapshotPage { content=List.copyOf(content); }
    }
}
