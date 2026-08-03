package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldFulfillCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldReleaseCommand;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일반 예약 조정자가 메뉴 홀드 생성·해제·이행 완료에 사용하는 공개 계약이다.
 *
 * <p>예약 조정자는 멱등 결과를 선점한 뒤 예약 aggregate와 수용량 버킷을 잠그고 이 계약을
 * 호출해야 한다. 생성·해제·이행 완료의 각 논리 작업에는 외부 Idempotency-Key와 다른 전역 고유
 * operationId를 발급하며, 다른 예약·버킷 집합·수량·source 작업이나 다른 명령 종류에 재사용하지 않는다.
 * 멱등 결과 replay에서는 이 서비스를 다시 호출하지 않는다.</p>
 *
 * <p>구현은 호출자의 트랜잭션에 참여해야 하며 새 트랜잭션을 시작하지 않는다. 메뉴홀드,
 * Store 또는 공통 오류는 원래 {@code ErrorCode}를 담은 {@code ServiceException} 그대로
 * 전달하고 예약 오류로 변환하지 않는다.</p>
 */
public interface MenuHoldService {

    /**
     * 선택 메뉴가 없으면 홀드를 만들지 않고 {@code NO_HOLD}를 반환한다. 선택 메뉴가 있으면
     * 모든 수량 확보와 {@code CONFIRMED} 홀드가 함께 성공해야 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    MenuHoldCommandResult create(MenuHoldCreateCommand command);

    /**
     * 예약 취소 시 원 확보 operation을 사용해 홀드를 해제하고 수량을 정확히 한 번 복구한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    MenuHoldCommandResult release(MenuHoldReleaseCommand command);

    /**
     * 예약 방문 완료 시 수량 복구 없이 홀드를 이행 완료로 종결한다. 이행 operationId는
     * 원 확보 operationId 및 다른 논리 작업의 operationId와 달라야 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    MenuHoldCommandResult fulfill(MenuHoldFulfillCommand command);
}
