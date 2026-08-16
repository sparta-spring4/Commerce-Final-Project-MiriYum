package com.miriyum.domain.store.entity;

import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.EnforcementCommand;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.entity.BaseEntity;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Store 단위 권한 판정에 사용하는 중앙 enforcement version과 원상 복구 snapshot이다. */
@Entity
@Table(name = "store_enforcement_states")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreEnforcementState extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_enforcement_state_id")
    private Long id;

    @Column(name = "store_id", nullable = false, unique = true)
    private Long storeId;

    @Column(name = "enforcement_version", nullable = false)
    private long enforcementVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_operation_status", nullable = false, length = 30)
    private OperationStatus baseOperationStatus;

    @Column(name = "base_reservation_enabled", nullable = false)
    private boolean baseReservationEnabled;

    @Column(name = "base_menu_hold_enabled", nullable = false)
    private boolean baseMenuHoldEnabled;

    @Column(name = "base_pickup_enabled", nullable = false)
    private boolean basePickupEnabled;

    @Column(name = "waiting_allowed", nullable = false)
    private boolean waitingAllowed;

    @Column(name = "store_management_allowed", nullable = false)
    private boolean storeManagementAllowed;

    @Column(name = "last_sanction_id")
    private Long lastSanctionId;

    public static StoreEnforcementState initial(Store store) {
        StoreEnforcementState state = new StoreEnforcementState();
        state.storeId = store.getId();
        state.baseOperationStatus = store.getOperationStatus();
        state.baseReservationEnabled = store.isReservationEnabled();
        state.baseMenuHoldEnabled = store.isMenuHoldEnabled();
        state.basePickupEnabled = store.isPickupEnabled();
        state.waitingAllowed = true;
        state.storeManagementAllowed = true;
        return state;
    }

    public void apply(EnforcementCommand command) {
        if (!storeId.equals(command.storeId())
                || enforcementVersion != command.expectedEnforcementVersion()) {
            throw new ServiceException(StoreErrorCode.STORE_ENFORCEMENT_VERSION_CONFLICT);
        }
        enforcementVersion++;
        lastSanctionId = command.sanctionId();
        waitingAllowed = command.waitingAllowed();
        storeManagementAllowed = command.storeManagementAllowed();
    }

    public void requireFeatureAllowed(RestrictedFeature feature) {
        boolean allowed = switch (feature) {
            case WAITING -> waitingAllowed;
            case STORE_MANAGEMENT -> storeManagementAllowed;
            default -> true;
        };
        if (!allowed) {
            throw new ServiceException(StoreErrorCode.STORE_FEATURE_RESTRICTED);
        }
    }

}
