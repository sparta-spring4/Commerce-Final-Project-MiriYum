package com.miriyum.domain.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.store.dto.contract.StoreReservationDepositPolicy;
import com.miriyum.domain.store.dto.contract.StoreReservationDepositPolicy.Status;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.store.repository.StoreReservationDepositPolicyRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreReservationDepositPolicyQueryServiceTest {

    private static final long STORE_ID = 41L;

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private StoreReservationDepositPolicyRepository policyRepository;

    private StoreReservationDepositPolicyQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new StoreReservationDepositPolicyQueryService(
                storeRepository, policyRepository);
    }

    @Test
    @DisplayName("없는 매장은 미구성 정책이 아니라 STORE_001로 거부한다")
    void rejectsMissingStoreBeforeReadingPolicy() {
        given(storeRepository.existsById(STORE_ID)).willReturn(false);

        assertThatThrownBy(() -> queryService.getCurrent(STORE_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_NOT_FOUND);
        then(storeRepository).should().existsById(STORE_ID);
        then(storeRepository).shouldHaveNoMoreInteractions();
        then(policyRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("정책 행이 없는 기존 매장은 숫자 fallback 없는 미구성 상태다")
    void returnsUnconfiguredWhenPolicyRowDoesNotExist() {
        given(storeRepository.existsById(STORE_ID)).willReturn(true);
        given(policyRepository.findById(STORE_ID)).willReturn(Optional.empty());

        StoreReservationDepositPolicy result = queryService.getCurrent(STORE_ID);

        assertThat(result).isEqualTo(new StoreReservationDepositPolicy(
                STORE_ID, Status.UNCONFIGURED, OptionalInt.empty(), OptionalLong.empty()));
        thenRepositoriesReadOnce();
    }

    @Test
    @DisplayName("비활성 정책 행은 마지막 비율과 Store revision을 보존한다")
    void mapsDisabledPolicyFromOneCurrentRow() {
        com.miriyum.domain.store.entity.StoreReservationDepositPolicy policy =
                com.miriyum.domain.store.entity.StoreReservationDepositPolicy.create(
                        STORE_ID, false, 10);
        given(storeRepository.existsById(STORE_ID)).willReturn(true);
        given(policyRepository.findById(STORE_ID)).willReturn(Optional.of(policy));

        StoreReservationDepositPolicy result = queryService.getCurrent(STORE_ID);

        assertThat(result).isEqualTo(new StoreReservationDepositPolicy(
                STORE_ID, Status.DISABLED, OptionalInt.of(10), OptionalLong.of(1L)));
        thenRepositoriesReadOnce();
    }

    @Test
    @DisplayName("활성 정책 행은 현재 비율과 Store revision을 같은 행에서 매핑한다")
    void mapsEnabledPolicyFromOneCurrentRow() {
        com.miriyum.domain.store.entity.StoreReservationDepositPolicy policy =
                com.miriyum.domain.store.entity.StoreReservationDepositPolicy.create(
                        STORE_ID, false, 20);
        policy.update(true, 30);
        given(storeRepository.existsById(STORE_ID)).willReturn(true);
        given(policyRepository.findById(STORE_ID)).willReturn(Optional.of(policy));

        StoreReservationDepositPolicy result = queryService.getCurrent(STORE_ID);

        assertThat(result).isEqualTo(new StoreReservationDepositPolicy(
                STORE_ID, Status.ENABLED, OptionalInt.of(30), OptionalLong.of(2L)));
        thenRepositoriesReadOnce();
    }

    private void thenRepositoriesReadOnce() {
        then(storeRepository).should().existsById(STORE_ID);
        then(storeRepository).shouldHaveNoMoreInteractions();
        then(policyRepository).should().findById(STORE_ID);
        then(policyRepository).shouldHaveNoMoreInteractions();
    }
}
