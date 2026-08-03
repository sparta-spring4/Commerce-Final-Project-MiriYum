package com.miriyum.domain.store.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.store.core.dto.StorePickupTransactionEligibility;
import com.miriyum.domain.store.core.dto.StoreReservationTransactionEligibility;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StoreTransactionEligibilityServiceTest {

    private static final long STORE_ID = 7L;

    @Mock
    private StoreRepository storeRepository;

    private StoreTransactionEligibilityService eligibilityService;

    @BeforeEach
    void setUp() {
        eligibilityService = new StoreTransactionEligibilityService(storeRepository);
    }

    @Test
    void reservationEligibilityRejectsMissingStore() {
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.empty());

        assertStoreError(
                () -> eligibilityService.requireReservationTransactionEligibility(STORE_ID),
                StoreErrorCode.STORE_NOT_FOUND);
    }

    @Test
    void reservationEligibilityRejectsStoreWithoutApprovedVerification() {
        Store store = eligibleStore(true, true);
        ReflectionTestUtils.setField(store, "verificationStatus", null);
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

        assertStoreError(
                () -> eligibilityService.requireReservationTransactionEligibility(STORE_ID),
                StoreErrorCode.VERIFICATION_STATE_CONFLICT);
    }

    @Test
    void reservationEligibilityRejectsTemporarilyClosedStore() {
        Store store = eligibleStore(true, true);
        ReflectionTestUtils.setField(
                store, "operationStatus", OperationStatus.TEMPORARILY_CLOSED);
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

        assertStoreError(
                () -> eligibilityService.requireReservationTransactionEligibility(STORE_ID),
                StoreErrorCode.STORE_STATE_CONFLICT);
    }

    @Test
    void reservationEligibilityRejectsClosedStore() {
        Store store = eligibleStore(true, true);
        store.close();
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

        assertStoreError(
                () -> eligibilityService.requireReservationTransactionEligibility(STORE_ID),
                StoreErrorCode.STORE_STATE_CONFLICT);
    }

    @Test
    void reservationEligibilityRejectsDisabledReservationMode() {
        Store store = eligibleStore(false, true);
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

        assertStoreError(
                () -> eligibilityService.requireReservationTransactionEligibility(STORE_ID),
                StoreErrorCode.STORE_STATE_CONFLICT);
    }

    @Test
    void pickupEligibilityRejectsMissingStore() {
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.empty());

        assertStoreError(
                () -> eligibilityService.requirePickupTransactionEligibility(STORE_ID),
                StoreErrorCode.STORE_NOT_FOUND);
    }

    @Test
    void pickupEligibilityRejectsStoreWithoutApprovedVerification() {
        Store store = eligibleStore(true, true);
        ReflectionTestUtils.setField(store, "verificationStatus", null);
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

        assertStoreError(
                () -> eligibilityService.requirePickupTransactionEligibility(STORE_ID),
                StoreErrorCode.VERIFICATION_STATE_CONFLICT);
    }

    @Test
    void pickupEligibilityRejectsTemporarilyClosedStore() {
        Store store = eligibleStore(true, true);
        ReflectionTestUtils.setField(
                store, "operationStatus", OperationStatus.TEMPORARILY_CLOSED);
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

        assertStoreError(
                () -> eligibilityService.requirePickupTransactionEligibility(STORE_ID),
                StoreErrorCode.STORE_STATE_CONFLICT);
    }

    @Test
    void pickupEligibilityRejectsClosedStore() {
        Store store = eligibleStore(true, true);
        store.close();
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

        assertStoreError(
                () -> eligibilityService.requirePickupTransactionEligibility(STORE_ID),
                StoreErrorCode.STORE_STATE_CONFLICT);
    }

    @Test
    void pickupEligibilityRejectsStoreWithoutCentralPickupEligibility() {
        Store store = ineligiblePickupStore();
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

        assertStoreError(
                () -> eligibilityService.requirePickupTransactionEligibility(STORE_ID),
                StoreErrorCode.PICKUP_NOT_ELIGIBLE);
    }

    @Test
    void pickupEligibilityRejectsDisabledPickupMode() {
        Store store = eligibleStore(true, false);
        given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

        assertStoreError(
                () -> eligibilityService.requirePickupTransactionEligibility(STORE_ID),
                StoreErrorCode.STORE_STATE_CONFLICT);
    }

    @Test
    void reservationEligibilityReturnsPurposeSpecificProof() {
        given(storeRepository.findByIdForUpdate(STORE_ID))
                .willReturn(Optional.of(eligibleStore(true, true)));

        StoreReservationTransactionEligibility result =
                eligibilityService.requireReservationTransactionEligibility(STORE_ID);

        assertThat(result)
                .isEqualTo(new StoreReservationTransactionEligibility(STORE_ID));
        then(storeRepository).should().findByIdForUpdate(STORE_ID);
        then(storeRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    void pickupEligibilityReturnsPurposeSpecificProof() {
        given(storeRepository.findByIdForUpdate(STORE_ID))
                .willReturn(Optional.of(eligibleStore(true, true)));

        StorePickupTransactionEligibility result =
                eligibilityService.requirePickupTransactionEligibility(STORE_ID);

        assertThat(result)
                .isEqualTo(new StorePickupTransactionEligibility(STORE_ID));
        then(storeRepository).should().findByIdForUpdate(STORE_ID);
        then(storeRepository).shouldHaveNoMoreInteractions();
    }

    private void assertStoreError(ThrowingCallable invocation, StoreErrorCode expected) {
        assertThatThrownBy(invocation)
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(expected);
    }

    private Store eligibleStore(boolean reservationEnabled, boolean pickupEnabled) {
        return store(BusinessType.CAFE, reservationEnabled, pickupEnabled);
    }

    private Store ineligiblePickupStore() {
        return store(BusinessType.OTHER, true, false);
    }

    private Store store(
            BusinessType businessType,
            boolean reservationEnabled,
            boolean pickupEnabled
    ) {
        Store store = Store.create(
                11L,
                "1234567890",
                businessType,
                "미리윰",
                "",
                Region.SEOUL,
                "서울시 중구",
                "CAFE_BAKERY",
                Set.of("DATE"),
                reservationEnabled,
                true,
                pickupEnabled,
                "Asia/Seoul",
                LocalDateTime.of(2026, 7, 31, 12, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1");
        ReflectionTestUtils.setField(store, "id", STORE_ID);
        return store;
    }
}
