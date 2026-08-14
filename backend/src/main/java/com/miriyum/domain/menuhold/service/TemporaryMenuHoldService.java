package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.dto.TemporaryMenuHoldContracts;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ReservationHold 조정자가 호출자 트랜잭션 안에서 사용하는 MenuHold 소유 공개 계약이다. */
public interface TemporaryMenuHoldService {

    /** 저장된 임시 MenuHold 선택 의미를 변경 없이 확인한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    TemporaryMenuHoldContracts.Result verifyCreationReplay(
            TemporaryMenuHoldContracts.Replay command);

    /** 모든 선택 수량과 임시 MenuHold를 호출자 트랜잭션에서 함께 생성한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    TemporaryMenuHoldContracts.Result create(TemporaryMenuHoldContracts.Create command);

    /** ReservationHold 잠금 뒤 임시 MenuHold 루트만 비관적으로 선잠근다. */
    @Transactional(propagation = Propagation.MANDATORY)
    TemporaryMenuHoldContracts.Result lockForTransition(long reservationHoldId);

    /** 수용량 처리 뒤 상태와 필요한 재고 복구를 호출자 트랜잭션에 적용한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    TemporaryMenuHoldContracts.Result applyTransition(
            TemporaryMenuHoldContracts.ApplyTransition command);
}
