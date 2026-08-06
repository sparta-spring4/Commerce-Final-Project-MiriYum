package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldFulfillCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldReleaseCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldTerminationPresence;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일반 예약 조정자가 메뉴 홀드 생성·해제·이행 완료에 사용하는 공개 계약이다.
 *
 * <p>생성 조정자는 멱등 결과를 선점하고 수용량 버킷을 잠근 뒤 {@link #create}를 호출한다.
 * 취소 조정자는 멱등 결과를 선점하고 예약 aggregate를 잠근 뒤 수용량 버킷보다 먼저
 * {@link #lockForTermination}을 호출하고, 수용량 처리 뒤 연결 홀드가 있을 때만 {@link #release}를
 * 호출한다. 생성·해제·이행 완료의 각 논리 작업에는 외부 Idempotency-Key와 다른 전역 고유
 * operationId를 발급하며, 다른 예약·메뉴 집합·수량·명령 종류에 재사용하지 않는다. 멱등 결과
 * replay에서는 이 서비스를 다시 호출하지 않는다.</p>
 *
 * <p>구현은 호출자의 트랜잭션에 참여해야 하며 새 트랜잭션을 시작하지 않는다. 메뉴홀드,
 * Store 또는 공통 오류는 원래 {@code ErrorCode}를 담은 {@code ServiceException} 그대로
 * 전달하고 예약 오류로 변환하지 않는다.</p>
 */
public interface MenuHoldService {

    /**
     * 예약 취소 조정자가 멱등 결과와 예약 aggregate를 먼저 잠근 뒤, 수용량 처리 전에 연결 메뉴 홀드
     * 루트 행만 잠근다. 홀드 상태를 해석하거나 상태·메뉴 수량·원장을 변경하지 않는다.
     * {@code NO_HOLD}는 잠글 메뉴 홀드 행이 없다는 뜻이며 동시 생성 배제는 선행 Reservation
     * aggregate 잠금과 모든 합법적인 생성 경로가 같은 순서를 지키는 데 의존한다.
     *
     * @param reservationId 종결할 예약의 내부 식별자
     * @return 연결 홀드 루트 행의 존재 여부
     */
    @Transactional(propagation = Propagation.MANDATORY)
    MenuHoldTerminationPresence lockForTermination(long reservationId);

    /**
     * 선택 메뉴가 없으면 홀드를 만들지 않고 {@code NO_HOLD}를 반환한다. 선택 메뉴가 있으면
     * 모든 수량 확보와 {@code CONFIRMED} 홀드가 함께 성공해야 한다.
     * 메뉴 상태·자격 위반은 {@code MENU_HOLD_001}, 수량 부족은 {@code MENU_HOLD_002}로
     * 실패한다. Store·공통 오류를 포함한 {@code ServiceException}은 다른 오류로 변환하지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    MenuHoldCommandResult create(MenuHoldCreateCommand command);

    /**
     * 예약 취소 시 예약에 연결된 홀드와 내부 원 확보 operation을 찾아 수량을 정확히 한 번 복구한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    MenuHoldCommandResult release(MenuHoldReleaseCommand command);

    /**
     * 예약 방문 완료 시 수량 복구 없이 홀드를 이행 완료로 종결한다. 이행 operationId는
     * 다른 논리 작업의 operationId와 달라야 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    MenuHoldCommandResult fulfill(MenuHoldFulfillCommand command);
}
