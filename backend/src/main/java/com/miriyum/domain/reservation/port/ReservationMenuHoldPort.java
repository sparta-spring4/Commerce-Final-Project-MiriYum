package com.miriyum.domain.reservation.port;

import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldCreateCommand;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldItemSnapshot;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldResult;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldTerminationPresence;
import java.util.List;

/** Reservation이 메뉴 홀드 구현 세부사항 없이 사용하는 종결·스냅샷 포트다. */
public interface ReservationMenuHoldPort {

    ReservationMenuHoldTerminationPresence lockForTermination(long reservationId);

    ReservationMenuHoldResult create(ReservationMenuHoldCreateCommand command);

    ReservationMenuHoldResult release(long reservationId, String operationId);

    ReservationMenuHoldResult fulfill(long reservationId, String operationId);

    ReservationMenuHoldResult forfeit(long reservationId, String operationId);

    List<ReservationMenuHoldItemSnapshot> findSnapshots(long reservationId);
}
