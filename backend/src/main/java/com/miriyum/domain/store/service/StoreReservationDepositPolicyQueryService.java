package com.miriyum.domain.store.service;

import com.miriyum.domain.store.dto.contract.StoreReservationDepositPolicy;
import com.miriyum.domain.store.dto.contract.StoreReservationDepositPolicy.Status;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.store.repository.StoreReservationDepositPolicyRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Store가 소유한 현재 예약금 설정을 immutable 공개 계약으로 조회한다.
 *
 * <p>호출자는 먼저 같은 Store 행의 직렬화 잠금을 획득하고, 그 transaction 안에서 이
 * Service를 호출해야 한다. {@link Propagation#MANDATORY}는 transaction 존재만 강제하며
 * Store 잠금 획득을 대신하지 않는다.</p>
 */
@Service
public class StoreReservationDepositPolicyQueryService {

    private final StoreRepository storeRepository;
    private final StoreReservationDepositPolicyRepository policyRepository;

    StoreReservationDepositPolicyQueryService(
            StoreRepository storeRepository,
            StoreReservationDepositPolicyRepository policyRepository
    ) {
        this.storeRepository = storeRepository;
        this.policyRepository = policyRepository;
    }

    /**
     * caller가 잠근 Store의 현재 예약금 설정을 한 정책 행에서 조회한다.
     *
     * @param storeId 대상 매장 식별자
     * @return 행이 없으면 미구성 상태, 행이 있으면 설정 의도·비율·Store revision
     * @throws ServiceException Store가 존재하지 않는 경우
     * @throws IllegalTransactionStateException caller transaction 없이 호출한 경우
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public StoreReservationDepositPolicy getCurrent(long storeId) {
        if (!storeRepository.existsById(storeId)) {
            throw new ServiceException(StoreErrorCode.STORE_NOT_FOUND);
        }
        return policyRepository.findById(storeId)
                .map(this::toContract)
                .orElseGet(() -> new StoreReservationDepositPolicy(
                        storeId,
                        Status.UNCONFIGURED,
                        OptionalInt.empty(),
                        OptionalLong.empty()));
    }

    private StoreReservationDepositPolicy toContract(
            com.miriyum.domain.store.entity.StoreReservationDepositPolicy policy
    ) {
        return new StoreReservationDepositPolicy(
                policy.getStoreId(),
                policy.isEnabled() ? Status.ENABLED : Status.DISABLED,
                OptionalInt.of(policy.getRatePercent()),
                OptionalLong.of(policy.getPolicyVersion()));
    }
}
