package com.miriyum.domain.reservation.port;

import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldCommand;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldResult;

/** ReservationHold 조정자가 임시 메뉴 선점 구현을 scalar 값으로만 사용하는 포트다. */
public interface ReservationTemporaryMenuHoldPort {

    /** 저장된 메뉴 선택 의미가 생성 replay와 같은지 변경 없이 확인한다. */
    ReservationTemporaryMenuHoldResult verifyCreationReplay(
            ReservationTemporaryMenuHoldCommand.Replay command);

    /** 수용량 확보 뒤 선택 메뉴 수량과 임시 MenuHold를 호출자 트랜잭션에서 생성한다. */
    ReservationTemporaryMenuHoldResult create(
            ReservationTemporaryMenuHoldCommand.Create command);

    /** ReservationHold 잠금 직후 연결 임시 MenuHold 루트만 선잠근다. */
    ReservationTemporaryMenuHoldResult lockForTransition(long reservationHoldId);

    /** 수용량 처리 뒤 임시 MenuHold 상태와 필요한 메뉴 수량 복구를 적용한다. */
    ReservationTemporaryMenuHoldResult applyTransition(
            ReservationTemporaryMenuHoldCommand.ApplyTransition command);
}
