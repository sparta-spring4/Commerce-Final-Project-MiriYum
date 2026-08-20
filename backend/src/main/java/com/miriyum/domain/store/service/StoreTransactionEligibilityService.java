package com.miriyum.domain.store.service;

import com.miriyum.domain.store.dto.contract.StorePickupTransactionEligibility;
import com.miriyum.domain.store.dto.contract.StoreReservationTransactionEligibility;
import com.miriyum.domain.store.dto.contract.StoreMenuTransactionEligibility;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.VerificationStatus;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 후속 거래 도메인이 신규 거래를 확정하기 직전에 Store 자격을 잠금 검증한다.
 * 호출자가 시작한 실제 거래 생성 트랜잭션에 참여하며 isolation과 timeout도 호출자가 소유한다.
 */
@Service
public class StoreTransactionEligibilityService {

    private final StoreRepository storeRepository;
    private final StoreAdministrationService storeAdministrationService;

    StoreTransactionEligibilityService(
            StoreRepository storeRepository,
            StoreAdministrationService storeAdministrationService
    ) {
        this.storeRepository = storeRepository;
        this.storeAdministrationService = storeAdministrationService;
    }

    /**
     * 운영자 principal 없이 일반 예약 신규 거래 자격을 최종 잠금 검증한다.
     *
     * @param storeId 대상 매장 식별자
     * @return 일반 예약 거래 자격을 통과한 매장
     * @throws ServiceException 매장이 없거나 현재 일반 예약 거래를 받을 수 없는 경우
     * @throws IllegalTransactionStateException 활성 거래 생성 트랜잭션 없이 호출한 경우
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StoreReservationTransactionEligibility
            requireReservationTransactionEligibility(long storeId) {
        Store store = loadStore(storeId);
        requireOpenApproved(store);
        storeAdministrationService.requireFeatureAllowed(storeId, RestrictedFeature.RESERVATION);
        if (!store.isReservationEnabled()) {
            throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
        }
        return new StoreReservationTransactionEligibility(
                store.getId(), store.getName());
    }

    /**
     * 운영자 principal 없이 Pickup 신규 거래 자격을 최종 잠금 검증한다.
     *
     * @param storeId 대상 매장 식별자
     * @return Pickup 거래 자격을 통과한 매장
     * @throws ServiceException 매장이 없거나 현재 Pickup 거래를 받을 수 없는 경우
     * @throws IllegalTransactionStateException 활성 거래 생성 트랜잭션 없이 호출한 경우
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StorePickupTransactionEligibility
            requirePickupTransactionEligibility(long storeId) {
        Store store = loadStore(storeId);
        requireOpenApproved(store);
        storeAdministrationService.requireFeatureAllowed(storeId, RestrictedFeature.PICKUP);
        if (!store.isPickupEnabled()) {
            throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
        }
        return new StorePickupTransactionEligibility(
                store.getId(), store.getName(), store.getTimeZoneId());
    }

    /**
     * 메뉴 도메인이 Menu 잠금 전에 사용할 Store 상태와 기능 모드를 잠금 검증한다.
     *
     * @param storeId 대상 매장 식별자
     * @return 메뉴 거래 계산에 필요한 Store 기능 모드
     * @throws ServiceException 매장이 없거나 현재 신규 거래를 받을 수 없는 경우
     * @throws IllegalTransactionStateException 활성 거래 생성 트랜잭션 없이 호출한 경우
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StoreMenuTransactionEligibility requireMenuTransactionEligibility(long storeId) {
        Store store = loadStore(storeId);
        requireOpenApproved(store);
        storeAdministrationService.requireFeatureAllowed(storeId, RestrictedFeature.MENU_HOLD);
        return new StoreMenuTransactionEligibility(
                store.getId(),
                store.isReservationEnabled(),
                store.isMenuHoldEnabled(),
                store.isPickupEnabled());
    }

    /** 신규 대기 등록 직전에 Store 상태와 매장 단위 대기 제재를 잠금 검증한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireWaitingTransactionEligibility(long storeId) {
        Store store = loadStore(storeId);
        requireOpenApproved(store);
        storeAdministrationService.requireFeatureAllowed(storeId, RestrictedFeature.WAITING);
    }

    private Store loadStore(long storeId) {
        return storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
    }

    private void requireOpenApproved(Store store) {
        if (store.getVerificationStatus() != VerificationStatus.APPROVED) {
            throw new ServiceException(StoreErrorCode.VERIFICATION_STATE_CONFLICT);
        }
        if (store.getOperationStatus() != OperationStatus.OPEN) {
            throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
        }
    }
}
