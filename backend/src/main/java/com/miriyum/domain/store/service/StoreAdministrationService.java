package com.miriyum.domain.store.service;

import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.EnforcementCommand;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.EnforcementResult;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.ReleaseCommand;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.StoreSnapshot;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.StoreSnapshotPage;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.entity.StoreEnforcementState;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.repository.StoreEnforcementStateRepository;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import com.miriyum.domain.store.enums.OperationStatus;
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
                state.effectiveOperationStatus(),
                state.effectiveReservationEnabled(),
                state.effectiveMenuHoldEnabled(),
                state.effectivePickupEnabled(),
                state.isStoreManagementAllowed());
        return new EnforcementResult(
                store.getId(),
                state.getEnforcementVersion(),
                store.getOperationStatus(),
                store.isReservationEnabled(),
                store.isMenuHoldEnabled(),
                store.isPickupEnabled(),
                state.isWaitingAllowed(),
                state.isStoreManagementAllowed(),
                state.activeRestrictedFeatures());
    }

    @Transactional(readOnly = true)
    public void requireFeatureAllowed(long storeId, RestrictedFeature feature) {
        Optional<StoreEnforcementState> state = enforcementStates.findByStoreId(storeId);
        state.ifPresent(value -> value.requireFeatureAllowed(feature));
    }

    @Transactional(readOnly = true)
    public void requireStoreExists(long storeId) {
        if (!stores.existsById(storeId)) {
            throw new ServiceException(StoreErrorCode.STORE_NOT_FOUND);
        }
    }

    @Transactional(readOnly = true)
    public EnforcementResult inspect(long storeId) {
        Store store = stores.findById(storeId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
        Optional<StoreEnforcementState> state = enforcementStates.findByStoreId(storeId);
        return new EnforcementResult(storeId, state.map(StoreEnforcementState::getEnforcementVersion).orElse(0L),
                store.getOperationStatus(), store.isReservationEnabled(), store.isMenuHoldEnabled(),
                store.isPickupEnabled(), state.map(StoreEnforcementState::isWaitingAllowed).orElse(true),
                state.map(StoreEnforcementState::isStoreManagementAllowed).orElse(true),
                state.map(StoreEnforcementState::activeRestrictedFeatures).orElse(Set.of()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EnforcementResult release(ReleaseCommand command) {
        Store store = stores.findByIdForUpdate(command.storeId())
                .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
        StoreEnforcementState state = enforcementStates.findByStoreIdForUpdate(command.storeId())
                .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_ENFORCEMENT_VERSION_CONFLICT));
        state.release(command);
        store.applyPlatformEnforcement(state.effectiveOperationStatus(), state.effectiveReservationEnabled(),
                state.effectiveMenuHoldEnabled(), state.effectivePickupEnabled(), state.isStoreManagementAllowed());
        return new EnforcementResult(store.getId(), state.getEnforcementVersion(), store.getOperationStatus(),
                store.isReservationEnabled(), store.isMenuHoldEnabled(), store.isPickupEnabled(),
                state.isWaitingAllowed(), state.isStoreManagementAllowed(), state.activeRestrictedFeatures());
    }

    @Transactional(readOnly = true)
    public StoreSnapshotPage search(String keyword, OperationStatus status, int page, int size) {
        var result=stores.searchForPlatformAdministration(keyword==null||keyword.isBlank()?null:keyword.strip(),
                status, PageRequest.of(page,size));
        var snapshots=result.getContent().stream().map(this::snapshot).toList();
        return new StoreSnapshotPage(snapshots,page,size,result.getTotalElements(),result.getTotalPages());
    }
    @Transactional(readOnly = true)
    public StoreSnapshot get(long storeId) {
        return snapshot(stores.findById(storeId).orElseThrow(()->new ServiceException(StoreErrorCode.STORE_NOT_FOUND)));
    }
    private StoreSnapshot snapshot(Store store) {
        long version=enforcementStates.findByStoreId(store.getId()).map(StoreEnforcementState::getEnforcementVersion).orElse(0L);
        return new StoreSnapshot(store.getId(),store.getName(),store.getStoreOperatorAccountId(),
                store.getOperationStatus(),store.isReservationEnabled(),store.isMenuHoldEnabled(),
                store.isPickupEnabled(),version,store.getCreatedAt());
    }

}
