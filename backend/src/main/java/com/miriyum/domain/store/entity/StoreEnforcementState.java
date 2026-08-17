package com.miriyum.domain.store.entity;

import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.EnforcementCommand;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.ReleaseCommand;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "active_enforcements", nullable = false, columnDefinition = "json")
    private List<ActiveEnforcement> activeEnforcements;

    public static StoreEnforcementState initial(Store store) {
        StoreEnforcementState state = new StoreEnforcementState();
        state.storeId = store.getId();
        state.baseOperationStatus = store.getOperationStatus();
        state.baseReservationEnabled = store.isReservationEnabled();
        state.baseMenuHoldEnabled = store.isMenuHoldEnabled();
        state.basePickupEnabled = store.isPickupEnabled();
        state.waitingAllowed = true;
        state.storeManagementAllowed = true;
        state.activeEnforcements = List.of();
        return state;
    }

    public void apply(EnforcementCommand command) {
        if (!storeId.equals(command.storeId())
                || enforcementVersion != command.expectedEnforcementVersion()) {
            throw new ServiceException(StoreErrorCode.STORE_ENFORCEMENT_VERSION_CONFLICT);
        }
        if (activeEnforcements.stream().anyMatch(value -> value.sanctionId() == command.sanctionId())) {
            throw new ServiceException(StoreErrorCode.STORE_ENFORCEMENT_VERSION_CONFLICT);
        }
        ArrayList<ActiveEnforcement> next = new ArrayList<>(activeEnforcements);
        next.add(new ActiveEnforcement(command.sanctionId(), command.operationStatus(), command.restrictedFeatures()));
        activeEnforcements = List.copyOf(next);
        enforcementVersion++;
        recompose();
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

    public void release(ReleaseCommand command) {
        if (!storeId.equals(command.storeId())
                || activeEnforcements.stream().noneMatch(value -> value.sanctionId() == command.sanctionId())) {
            throw new ServiceException(StoreErrorCode.STORE_ENFORCEMENT_VERSION_CONFLICT);
        }
        activeEnforcements = activeEnforcements.stream()
                .filter(value -> value.sanctionId() != command.sanctionId())
                .toList();
        enforcementVersion++;
        recompose();
    }

    public OperationStatus effectiveOperationStatus() {
        if (activeEnforcements.stream().anyMatch(value -> value.operationStatus() == OperationStatus.CLOSED)) {
            return OperationStatus.CLOSED;
        }
        if (activeEnforcements.stream().anyMatch(value -> value.operationStatus() == OperationStatus.TEMPORARILY_CLOSED)) {
            return OperationStatus.TEMPORARILY_CLOSED;
        }
        return baseOperationStatus;
    }

    public Set<RestrictedFeature> activeRestrictedFeatures() {
        EnumSet<RestrictedFeature> restricted = EnumSet.noneOf(RestrictedFeature.class);
        activeEnforcements.forEach(value -> restricted.addAll(value.restrictedFeatures()));
        return Set.copyOf(restricted);
    }

    public boolean effectiveReservationEnabled() {
        return baseReservationEnabled && !activeRestrictedFeatures().contains(RestrictedFeature.RESERVATION);
    }

    public boolean effectiveMenuHoldEnabled() {
        return baseMenuHoldEnabled && !activeRestrictedFeatures().contains(RestrictedFeature.MENU_HOLD);
    }

    public boolean effectivePickupEnabled() {
        return basePickupEnabled && !activeRestrictedFeatures().contains(RestrictedFeature.PICKUP);
    }

    private void recompose() {
        Set<RestrictedFeature> restricted = activeRestrictedFeatures();
        waitingAllowed = !restricted.contains(RestrictedFeature.WAITING);
        storeManagementAllowed = !restricted.contains(RestrictedFeature.STORE_MANAGEMENT);
        lastSanctionId = activeEnforcements.stream()
                .max(Comparator.comparingLong(ActiveEnforcement::sanctionId))
                .map(ActiveEnforcement::sanctionId)
                .orElse(null);
    }

    public record ActiveEnforcement(long sanctionId, OperationStatus operationStatus,
                                    Set<RestrictedFeature> restrictedFeatures) {
        public ActiveEnforcement {
            if (sanctionId <= 0 || restrictedFeatures == null) {
                throw new IllegalArgumentException("active store enforcement is invalid");
            }
            restrictedFeatures = Set.copyOf(restrictedFeatures);
        }
    }

}
