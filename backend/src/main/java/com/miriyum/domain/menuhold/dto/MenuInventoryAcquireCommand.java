package com.miriyum.domain.menuhold.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 픽업 조정자가 호출자 트랜잭션에서 실행할 메뉴 수량 확보 명령이다. */
public record MenuInventoryAcquireCommand(
        String operationId,
        List<MenuInventoryAcquireSelection> selections
) {
    public MenuInventoryAcquireCommand {
        requireText(operationId, "operationId");
        if (selections == null || selections.isEmpty()) {
            throw new IllegalArgumentException("selections must not be empty");
        }
        if (selections.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("selection must not be null");
        }
        selections = normalize(selections);
    }

    private static List<MenuInventoryAcquireSelection> normalize(
            List<MenuInventoryAcquireSelection> selections
    ) {
        Map<SelectionKey, Integer> quantities = new LinkedHashMap<>();
        try {
            for (MenuInventoryAcquireSelection selection : selections) {
                quantities.merge(SelectionKey.from(selection), selection.quantity(), Math::addExact);
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "inventory selection quantity sum exceeds integer range", exception);
        }
        List<SelectionKey> keys = new ArrayList<>(quantities.keySet());
        keys.sort(Comparator.naturalOrder());
        return keys.stream().map(key -> key.toSelection(quantities.get(key))).toList();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record SelectionKey(
            long menuId,
            LocalDate serviceDate,
            LocalTime startTime,
            LocalDate endDate,
            LocalTime endTime,
            long inventoryPolicyVersion
    ) implements Comparable<SelectionKey> {
        private static SelectionKey from(MenuInventoryAcquireSelection selection) {
            return new SelectionKey(
                    selection.menuId(), selection.serviceDate(), selection.startTime(),
                    selection.endDate(), selection.endTime(),
                    selection.inventoryPolicyVersion());
        }

        private MenuInventoryAcquireSelection toSelection(int quantity) {
            return new MenuInventoryAcquireSelection(
                    menuId, serviceDate, startTime, endDate, endTime,
                    inventoryPolicyVersion, quantity);
        }

        @Override
        public int compareTo(SelectionKey other) {
            int menuOrder = Long.compare(menuId, other.menuId);
            if (menuOrder != 0) {
                return menuOrder;
            }
            int dateOrder = serviceDate.compareTo(other.serviceDate);
            if (dateOrder != 0) {
                return dateOrder;
            }
            int startOrder = startTime.compareTo(other.startTime);
            if (startOrder != 0) {
                return startOrder;
            }
            int endDateOrder = endDate.compareTo(other.endDate);
            if (endDateOrder != 0) {
                return endDateOrder;
            }
            int endTimeOrder = endTime.compareTo(other.endTime);
            if (endTimeOrder != 0) {
                return endTimeOrder;
            }
            return Long.compare(inventoryPolicyVersion, other.inventoryPolicyVersion);
        }
    }
}
