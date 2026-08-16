package com.miriyum.domain.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.EnforcementCommand;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.ReleaseCommand;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.entity.StoreEnforcementState;
import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.repository.StoreEnforcementStateRepository;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StoreAdministrationServiceIT {

    private static final long TARGET_STORE_ID = 7L;
    private static final long SIBLING_STORE_ID = 8L;

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private StoreEnforcementStateRepository enforcementStates;

    private StoreAdministrationService service;

    @BeforeEach
    void setUp() {
        service = new StoreAdministrationService(storeRepository, enforcementStates);
    }

    @Test
    void restrictionChangesOnlyTargetStoreAndIncrementsVersion() {
        Store target = openStore(TARGET_STORE_ID, 11L);
        Store sibling = openStore(SIBLING_STORE_ID, 11L);
        given(storeRepository.findByIdForUpdate(TARGET_STORE_ID)).willReturn(Optional.of(target));
        given(enforcementStates.findByStoreIdForUpdate(TARGET_STORE_ID))
                .willReturn(Optional.empty());
        given(enforcementStates.save(any(StoreEnforcementState.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        var result = service.apply(new EnforcementCommand(
                TARGET_STORE_ID,
                0L,
                91L,
                OperationStatus.OPEN,
                false,
                true,
                true,
                true,
                true,
                Set.of(RestrictedFeature.RESERVATION)));

        assertThat(result.enforcementVersion()).isEqualTo(1L);
        assertThat(target.isReservationEnabled()).isFalse();
        assertThat(sibling.isReservationEnabled()).isTrue();
    }

    @Test
    void staleEnforcementVersionIsRejected() {
        Store target = openStore(TARGET_STORE_ID, 11L);
        StoreEnforcementState state = StoreEnforcementState.initial(target);
        state.apply(new EnforcementCommand(
                TARGET_STORE_ID,
                0L,
                91L,
                OperationStatus.TEMPORARILY_CLOSED,
                false,
                false,
                false,
                false,
                false,
                Set.of(RestrictedFeature.RESERVATION)));
        given(storeRepository.findByIdForUpdate(TARGET_STORE_ID)).willReturn(Optional.of(target));
        given(enforcementStates.findByStoreIdForUpdate(TARGET_STORE_ID))
                .willReturn(Optional.of(state));

        assertThatThrownBy(() -> service.apply(new EnforcementCommand(
                TARGET_STORE_ID,
                0L,
                92L,
                OperationStatus.OPEN,
                true,
                true,
                true,
                true,
                true,
                Set.of())))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.STORE_ENFORCEMENT_VERSION_CONFLICT));
    }

    @Test
    void waitingRestrictionIsRejectedByStoreScopedAuthorityCheck() {
        Store target = openStore(TARGET_STORE_ID, 11L);
        StoreEnforcementState state = StoreEnforcementState.initial(target);
        state.apply(new EnforcementCommand(
                TARGET_STORE_ID,
                0L,
                91L,
                OperationStatus.OPEN,
                true,
                true,
                true,
                false,
                true,
                Set.of(RestrictedFeature.WAITING)));
        given(enforcementStates.findByStoreId(TARGET_STORE_ID)).willReturn(Optional.of(state));

        assertThatThrownBy(() ->
                service.requireFeatureAllowed(TARGET_STORE_ID, RestrictedFeature.WAITING))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.STORE_FEATURE_RESTRICTED));
    }

    @Test
    void storeManagementRestrictionIsHeldByTargetStoreOnly() {
        Store target = openStore(TARGET_STORE_ID, 11L);
        Store sibling = openStore(SIBLING_STORE_ID, 11L);
        given(storeRepository.findByIdForUpdate(TARGET_STORE_ID)).willReturn(Optional.of(target));
        given(enforcementStates.findByStoreIdForUpdate(TARGET_STORE_ID))
                .willReturn(Optional.empty());
        given(enforcementStates.save(any(StoreEnforcementState.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        service.apply(new EnforcementCommand(
                TARGET_STORE_ID,
                0L,
                91L,
                OperationStatus.OPEN,
                true,
                true,
                true,
                true,
                false,
                Set.of(RestrictedFeature.STORE_MANAGEMENT)));

        assertThatThrownBy(target::requirePlatformManagementAllowed)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.STORE_FEATURE_RESTRICTED));
        sibling.requirePlatformManagementAllowed();
    }

    @Test
    void releasingLatestOverlappingSanctionKeepsEarlierRestriction() {
        Store target = openStore(TARGET_STORE_ID, 11L);
        StoreEnforcementState state = StoreEnforcementState.initial(target);
        state.apply(new EnforcementCommand(TARGET_STORE_ID, 0L, 91L, OperationStatus.OPEN,
                false, true, true, true, true, Set.of(RestrictedFeature.RESERVATION)));
        state.apply(new EnforcementCommand(TARGET_STORE_ID, 1L, 92L, OperationStatus.OPEN,
                false, true, true, false, true, Set.of(RestrictedFeature.WAITING)));
        target.applyPlatformEnforcement(OperationStatus.OPEN, false, true, true, true);
        given(storeRepository.findByIdForUpdate(TARGET_STORE_ID)).willReturn(Optional.of(target));
        given(enforcementStates.findByStoreIdForUpdate(TARGET_STORE_ID)).willReturn(Optional.of(state));

        var result = service.release(new ReleaseCommand(TARGET_STORE_ID, 92L));

        assertThat(result.reservationEnabled()).isFalse();
        assertThat(result.waitingAllowed()).isTrue();
        assertThat(result.restrictedFeatures()).containsExactly(RestrictedFeature.RESERVATION);
    }

    private Store openStore(long storeId, long operatorId) {
        Store store = Store.create(
                operatorId,
                String.format("%010d", storeId),
                BusinessType.CAFE,
                "미리윰 " + storeId,
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of("DATE"),
                true,
                true,
                true,
                "Asia/Seoul",
                LocalDateTime.of(2026, 8, 16, 12, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1");
        ReflectionTestUtils.setField(store, "id", storeId);
        return store;
    }
}
