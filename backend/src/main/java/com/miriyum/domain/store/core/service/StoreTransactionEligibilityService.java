package com.miriyum.domain.store.core.service;

import com.miriyum.domain.store.core.dto.StorePickupTransactionEligibility;
import com.miriyum.domain.store.core.dto.StoreReservationTransactionEligibility;
import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.PickupEligibility;
import com.miriyum.domain.store.core.enums.VerificationStatus;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 후속 거래 도메인에 Store 자체의 현재 거래 자격을 공개한다.
 */
@Service
public class StoreTransactionEligibilityService {

    private final StoreRepository storeRepository;

    StoreTransactionEligibilityService(StoreRepository storeRepository) {
        this.storeRepository = storeRepository;
    }

    /**
     * 운영자 principal 없이 일반 예약 신규 거래 가능 상태를 검증한다.
     *
     * @param storeId 대상 매장 식별자
     * @return 일반 예약 거래 자격을 통과한 매장
     * @throws ServiceException 매장이 없거나 현재 일반 예약 거래를 받을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public StoreReservationTransactionEligibility
            requireReservationTransactionEligibility(long storeId) {
        Store store = loadStore(storeId);
        requireOpenApproved(store);
        if (!store.isReservationEnabled()) {
            throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
        }
        return new StoreReservationTransactionEligibility(store.getId());
    }

    /**
     * 운영자 principal 없이 Pickup 신규 거래 가능 상태를 검증한다.
     *
     * @param storeId 대상 매장 식별자
     * @return Pickup 거래 자격을 통과한 매장
     * @throws ServiceException 매장이 없거나 현재 Pickup 거래를 받을 수 없는 경우
     */
    @Transactional(readOnly = true)
    public StorePickupTransactionEligibility
            requirePickupTransactionEligibility(long storeId) {
        Store store = loadStore(storeId);
        requireOpenApproved(store);
        if (store.getPickupEligibility() != PickupEligibility.ELIGIBLE) {
            throw new ServiceException(StoreErrorCode.PICKUP_NOT_ELIGIBLE);
        }
        if (!store.isPickupEnabled()) {
            throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
        }
        return new StorePickupTransactionEligibility(store.getId());
    }

    private Store loadStore(long storeId) {
        return storeRepository.findById(storeId)
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
