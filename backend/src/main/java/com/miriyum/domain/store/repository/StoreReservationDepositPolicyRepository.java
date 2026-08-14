package com.miriyum.domain.store.repository;

import com.miriyum.domain.store.entity.StoreReservationDepositPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

/** 현재 매장 예약금 정책 행을 일반 조회하고 저장하는 영속성 경계다. */
public interface StoreReservationDepositPolicyRepository
        extends JpaRepository<StoreReservationDepositPolicy, Long> {
}
