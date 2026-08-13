package com.miriyum.domain.menuhold.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.TreeMap;

/** MenuHold가 소유하는 임시 선점 scalar 명령과 결과 계약이다. */
public final class TemporaryMenuHoldContracts {

    private TemporaryMenuHoldContracts() {
    }

    /** MenuHold가 소유하는 정규 메뉴별 수량이다. */
    public record Selection(long menuId, int quantity) {
        public Selection {
            requirePositive(menuId, "menuId");
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
        }
    }

    /** 저장된 임시 MenuHold와 생성 입력 의미를 비교하는 명령이다. */
    public record Replay(long reservationHoldId, List<Selection> selections) {
        public Replay {
            requirePositive(reservationHoldId, "reservationHoldId");
            selections = canonicalSelections(selections);
        }
    }

    /** 현재 재고를 확보하고 정확한 만료 시각의 임시 MenuHold를 만드는 명령이다. */
    public record Create(
            long reservationHoldId,
            long storeId,
            long consumerAccountId,
            LocalDate serviceDate,
            LocalTime startTime,
            LocalDate endDate,
            LocalTime endTime,
            Instant startAt,
            Instant serviceEndAt,
            Instant expiresAt,
            List<Selection> selections
    ) {
        public Create {
            requirePositive(reservationHoldId, "reservationHoldId");
            requirePositive(storeId, "storeId");
            requirePositive(consumerAccountId, "consumerAccountId");
            if (serviceDate == null) {
                throw new IllegalArgumentException("serviceDate must not be null");
            }
            if (startTime == null || endDate == null || endTime == null
                    || !LocalDateTime.of(serviceDate, startTime)
                            .isBefore(LocalDateTime.of(endDate, endTime))) {
                throw new IllegalArgumentException("service time range must be increasing");
            }
            if (startAt == null || serviceEndAt == null || !startAt.isBefore(serviceEndAt)) {
                throw new IllegalArgumentException(
                        "resolved service time range must be increasing");
            }
            if (expiresAt == null) {
                throw new IllegalArgumentException("expiresAt must not be null");
            }
            selections = canonicalSelections(selections);
        }
    }

    /** 잠긴 임시 MenuHold에 검증된 목표 상태를 적용하는 명령이다. */
    public record ApplyTransition(
            long reservationHoldId,
            Target target,
            String operationId,
            Long finalReservationId
    ) {
        public ApplyTransition {
            requirePositive(reservationHoldId, "reservationHoldId");
            if (target == null) {
                throw new IllegalArgumentException("target must not be null");
            }
            requireOperationId(operationId);
            requireFinalLinkage(target, finalReservationId);
        }
    }

    /** 영속 타입을 노출하지 않는 임시 MenuHold 존재·상태·연결 결과다. */
    public record Result(Presence presence, State state, Long finalReservationId) {
        public Result {
            if (presence == null) {
                throw new IllegalArgumentException("presence must not be null");
            }
            if (presence == Presence.NO_HOLD) {
                if (state != null || finalReservationId != null) {
                    throw new IllegalArgumentException(
                            "NO_HOLD must not expose state or finalReservationId");
                }
            } else if (state == null) {
                throw new IllegalArgumentException("HOLD_PRESENT requires state");
            }
            if (state == State.CONFIRMED || state == State.FULFILLED) {
                if (finalReservationId == null || finalReservationId <= 0) {
                    throw new IllegalArgumentException(
                            state + " requires a positive finalReservationId");
                }
            } else if (state == State.RELEASED) {
                if (finalReservationId != null && finalReservationId <= 0) {
                    throw new IllegalArgumentException(
                            "RELEASED finalReservationId must be positive when present");
                }
            } else if (finalReservationId != null) {
                throw new IllegalArgumentException(
                        "finalReservationId is allowed only for final-linked terminal states");
            }
        }
    }

    /** 임시 MenuHold 루트 행의 존재 의미다. */
    public enum Presence {
        NO_HOLD,
        HOLD_PRESENT
    }

    /** 임시 MenuHold 공개 상태 의미다. */
    public enum State {
        ACTIVE,
        RECONCILIATION_REQUIRED,
        CONFIRMED,
        RELEASED,
        EXPIRED,
        FULFILLED
    }

    /** 임시 MenuHold에 적용 가능한 명시적 종결 목표다. */
    public enum Target {
        CONFIRM,
        RELEASE,
        EXPIRE,
        REQUIRE_RECONCILIATION
    }

    private static List<Selection> canonicalSelections(List<Selection> selections) {
        if (selections == null) {
            throw new IllegalArgumentException("selections must not be null");
        }
        if (selections.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("selections must not contain null");
        }
        TreeMap<Long, Integer> quantities = new TreeMap<>();
        for (Selection selection : selections) {
            try {
                quantities.merge(selection.menuId(), selection.quantity(), Math::addExact);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "selection quantity sum exceeds integer range", exception);
            }
        }
        return quantities.entrySet().stream()
                .map(entry -> new Selection(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static void requireFinalLinkage(Target target, Long finalReservationId) {
        if (target == Target.CONFIRM) {
            if (finalReservationId == null || finalReservationId <= 0) {
                throw new IllegalArgumentException(
                        "CONFIRM requires a positive finalReservationId");
            }
            return;
        }
        if (finalReservationId != null) {
            throw new IllegalArgumentException(
                    "finalReservationId is allowed only for CONFIRM");
        }
    }

    private static void requireOperationId(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        if (operationId.length() > 100) {
            throw new IllegalArgumentException("operationId must not exceed 100 characters");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
