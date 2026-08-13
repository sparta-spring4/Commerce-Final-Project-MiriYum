package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.dto.TemporaryMenuHoldContracts;
import com.miriyum.domain.menuhold.entity.MenuHold;
import com.miriyum.domain.menuhold.entity.MenuHoldStatus;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.stereotype.Service;

/** 임시 MenuHold의 상태와 최종 Reservation 연결만 결정하는 순수 종결 정책이다. */
@Service
public class MenuHoldTerminalService {

    /**
     * 검증된 목표 상태를 임시 MenuHold에 적용한다.
     *
     * @return 이번 호출이 실제 상태를 전이했으면 {@code true}, 같은 의미 replay면 {@code false}
     */
    public boolean apply(
            MenuHold hold,
            TemporaryMenuHoldContracts.Target target,
            Long finalReservationId
    ) {
        validateArguments(hold, target, finalReservationId);
        requireTemporary(hold);
        return switch (target) {
            case CONFIRM -> confirm(hold, finalReservationId);
            case RELEASE -> release(hold);
            case EXPIRE -> expire(hold);
            case REQUIRE_RECONCILIATION -> requireReconciliation(hold);
        };
    }

    private static boolean confirm(MenuHold hold, long finalReservationId) {
        if (hold.getStatus() == MenuHoldStatus.CONFIRMED) {
            if (hold.getReservationId() != null
                    && hold.getReservationId() == finalReservationId) {
                return false;
            }
            throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        requireStatus(hold, MenuHoldStatus.ACTIVE, MenuHoldStatus.RECONCILIATION_REQUIRED);
        try {
            hold.confirmTemporary(finalReservationId);
        } catch (IllegalStateException exception) {
            throw stateConflict(exception);
        }
        return true;
    }

    private static boolean release(MenuHold hold) {
        if (hold.getStatus() == MenuHoldStatus.RELEASED) {
            return false;
        }
        requireStatus(hold, MenuHoldStatus.ACTIVE, MenuHoldStatus.RECONCILIATION_REQUIRED);
        try {
            hold.releaseTemporary();
        } catch (IllegalStateException exception) {
            throw stateConflict(exception);
        }
        return true;
    }

    private static boolean expire(MenuHold hold) {
        if (hold.getStatus() == MenuHoldStatus.EXPIRED) {
            return false;
        }
        requireStatus(hold, MenuHoldStatus.ACTIVE);
        try {
            hold.expireTemporary();
        } catch (IllegalStateException exception) {
            throw stateConflict(exception);
        }
        return true;
    }

    private static boolean requireReconciliation(MenuHold hold) {
        if (hold.getStatus() == MenuHoldStatus.RECONCILIATION_REQUIRED) {
            return false;
        }
        requireStatus(hold, MenuHoldStatus.ACTIVE);
        try {
            hold.requireTemporaryReconciliation();
        } catch (IllegalStateException exception) {
            throw stateConflict(exception);
        }
        return true;
    }

    private static void validateArguments(
            MenuHold hold,
            TemporaryMenuHoldContracts.Target target,
            Long finalReservationId
    ) {
        if (hold == null) {
            throw new IllegalArgumentException("hold must not be null");
        }
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (target == TemporaryMenuHoldContracts.Target.CONFIRM) {
            if (finalReservationId == null || finalReservationId <= 0) {
                throw new IllegalArgumentException(
                        "CONFIRM requires a positive finalReservationId");
            }
        } else if (finalReservationId != null) {
            throw new IllegalArgumentException(
                    "finalReservationId is allowed only for CONFIRM");
        }
    }

    private static void requireTemporary(MenuHold hold) {
        if (hold.getReservationHoldId() == null || hold.getExpiresAt() == null) {
            throw new ServiceException(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        }
    }

    private static void requireStatus(MenuHold hold, MenuHoldStatus... allowed) {
        for (MenuHoldStatus status : allowed) {
            if (hold.getStatus() == status) {
                return;
            }
        }
        throw new ServiceException(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
    }

    private static ServiceException stateConflict(IllegalStateException cause) {
        ServiceException conflict =
                new ServiceException(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        conflict.initCause(cause);
        return conflict;
    }
}
