package com.miriyum.domain.reservation.port.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.TreeMap;

/** ReservationHold 조정자가 임시 MenuHold에 전달하는 scalar 명령 모음이다. */
public final class ReservationTemporaryMenuHoldCommand {

    private ReservationTemporaryMenuHoldCommand() {
    }

    /** 생성 replay에서 비교할 ReservationHold ID와 정규 메뉴 선택이다. */
    public record Replay(
            long reservationHoldId,
            List<ReservationTemporaryMenuHoldSelection> selections
    ) {
        public Replay {
            requirePositive(reservationHoldId, "reservationHoldId");
            selections = canonicalSelections(selections);
        }
    }

    /** 이미 확보된 ReservationHold에 같은 만료 시각으로 메뉴 수량을 결합하는 명령이다. */
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
            List<ReservationTemporaryMenuHoldSelection> selections
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

    /** 호출자가 검증한 종결 목표와 전역 operation ID를 적용하는 명령이다. */
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

    /** 임시 MenuHold에 적용 가능한 명시적 종결 목표다. */
    public enum Target {
        CONFIRM,
        RELEASE,
        EXPIRE,
        REQUIRE_RECONCILIATION
    }

    private static List<ReservationTemporaryMenuHoldSelection> canonicalSelections(
            List<ReservationTemporaryMenuHoldSelection> selections
    ) {
        if (selections == null) {
            throw new IllegalArgumentException("selections must not be null");
        }
        if (selections.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("selections must not contain null");
        }
        TreeMap<Long, Integer> quantities = new TreeMap<>();
        for (ReservationTemporaryMenuHoldSelection selection : selections) {
            try {
                quantities.merge(selection.menuId(), selection.quantity(), Math::addExact);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "selection quantity sum exceeds integer range", exception);
            }
        }
        return quantities.entrySet().stream()
                .map(entry -> new ReservationTemporaryMenuHoldSelection(
                        entry.getKey(), entry.getValue()))
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
