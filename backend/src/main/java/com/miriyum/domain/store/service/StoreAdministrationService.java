package com.miriyum.domain.store.service;

import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.EnforcementCommand;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.EnforcementResult;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.entity.StoreEnforcementState;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.repository.StoreEnforcementStateRepository;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 플랫폼 관리 기능이 사용할 Store 소유 공개 Service port다. */
@Service
public class StoreAdministrationService {

    private final StoreRepository stores;
    private final StoreEnforcementStateRepository enforcementStates;

    public StoreAdministrationService(
            StoreRepository stores,
            StoreEnforcementStateRepository enforcementStates
    ) {
        this.stores = stores;
        this.enforcementStates = enforcementStates;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EnforcementResult apply(EnforcementCommand command) {
        Store store = stores.findByIdForUpdate(command.storeId())
                .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
        StoreEnforcementState state = enforcementStates.findByStoreIdForUpdate(command.storeId())
                .orElseGet(() -> enforcementStates.save(StoreEnforcementState.initial(store)));
        state.apply(command);
        store.applyPlatformEnforcement(
                command.operationStatus(),
                command.reservationEnabled(),
                command.menuHoldEnabled(),
                command.pickupEnabled(),
                command.storeManagementAllowed());
        return new EnforcementResult(
                store.getId(),
                state.getEnforcementVersion(),
                store.getOperationStatus(),
                store.isReservationEnabled(),
                store.isMenuHoldEnabled(),
                store.isPickupEnabled(),
                state.isWaitingAllowed(),
                state.isStoreManagementAllowed(),
                command.restrictedFeatures());
    }

    @Transactional(readOnly = true)
    public void requireFeatureAllowed(long storeId, RestrictedFeature feature) {
        Optional<StoreEnforcementState> state = enforcementStates.findByStoreId(storeId);
        state.ifPresent(value -> value.requireFeatureAllowed(feature));
    }

}
