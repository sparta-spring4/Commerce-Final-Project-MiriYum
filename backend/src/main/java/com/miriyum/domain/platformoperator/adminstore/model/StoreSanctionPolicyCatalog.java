package com.miriyum.domain.platformoperator.adminstore.model;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.SanctionShape;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionType;
import com.miriyum.domain.platformoperator.adminstore.exception.AdminStoreErrorCode;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.*;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class StoreSanctionPolicyCatalog {
    private static final Set<RestrictedFeature> TRANSACTION_FEATURES = EnumSet.of(
            RestrictedFeature.RESERVATION, RestrictedFeature.WAITING,
            RestrictedFeature.MENU_HOLD, RestrictedFeature.PICKUP);

    public void validate(SanctionShape shape, Instant now) {
        if (shape.type()==SanctionType.WARNING && !shape.restrictedFeatures().isEmpty()) reject();
        if (shape.type()==SanctionType.FEATURE_RESTRICTION && shape.restrictedFeatures().isEmpty()) reject();
        if (shape.type()==SanctionType.TEMPORARY_SUSPENSION) {
            if (shape.endsAt()==null || !shape.endsAt().isAfter(now)
                    || shape.endsAt().isAfter(now.plus(90, ChronoUnit.DAYS))) reject();
        }
        if (shape.type()==SanctionType.PERMANENT_EXIT && shape.endsAt()!=null) reject();
    }
    public boolean requiresImpact(SanctionShape shape) { return shape.type()!=SanctionType.WARNING; }
    public boolean requiresApproval(SanctionShape shape) {
        return shape.type()==SanctionType.TEMPORARY_SUSPENSION || shape.type()==SanctionType.PERMANENT_EXIT;
    }
    public EnforcementCommand command(long storeId, long version, long sanctionId,
                                      EnforcementResult current, SanctionShape shape) {
        Set<RestrictedFeature> restricted = switch (shape.type()) {
            case WARNING -> Set.of();
            case FEATURE_RESTRICTION -> shape.restrictedFeatures();
            case TEMPORARY_SUSPENSION -> Set.copyOf(TRANSACTION_FEATURES);
            case PERMANENT_EXIT -> EnumSet.allOf(RestrictedFeature.class);
        };
        OperationStatus operation = switch (shape.type()) {
            case TEMPORARY_SUSPENSION -> OperationStatus.TEMPORARILY_CLOSED;
            case PERMANENT_EXIT -> OperationStatus.CLOSED;
            default -> current.operationStatus();
        };
        return new EnforcementCommand(storeId, version, sanctionId, operation,
                current.reservationEnabled() && !restricted.contains(RestrictedFeature.RESERVATION),
                current.menuHoldEnabled() && !restricted.contains(RestrictedFeature.MENU_HOLD),
                current.pickupEnabled() && !restricted.contains(RestrictedFeature.PICKUP),
                current.waitingAllowed() && !restricted.contains(RestrictedFeature.WAITING),
                current.storeManagementAllowed() && !restricted.contains(RestrictedFeature.STORE_MANAGEMENT), restricted);
    }
    private static void reject() { throw new ServiceException(AdminStoreErrorCode.POLICY_VIOLATION); }
}
