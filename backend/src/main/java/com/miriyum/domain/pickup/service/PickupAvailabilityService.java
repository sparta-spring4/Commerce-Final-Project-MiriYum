package com.miriyum.domain.pickup.service;

import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityDateQuery;
import com.miriyum.domain.menuhold.service.MenuInventoryTransactionService;
import com.miriyum.domain.pickup.dto.response.PickupAvailability;
import com.miriyum.domain.pickup.dto.response.PickupAvailabilitySlot;
import com.miriyum.domain.pickup.dto.response.PickupAvailabilityStatus;
import com.miriyum.domain.pickup.dto.response.PickupAvailableMenu;
import com.miriyum.domain.pickup.exception.PickupErrorCode;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.search.dto.PublicMenu;
import com.miriyum.domain.store.search.dto.PublicStoreDetail;
import com.miriyum.domain.store.search.service.StorePublicQueryService;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 잠금을 사용하지 않고 공개 Store·MenuHold 계약만 조합하는 픽업 가용성 조회다. */
@Service
public class PickupAvailabilityService {

    private final StorePublicQueryService storePublicQueryService;
    private final MenuInventoryTransactionService menuInventoryTransactionService;
    private final PickupIntervalTimePolicy intervalTimePolicy;

    public PickupAvailabilityService(
            StorePublicQueryService storePublicQueryService,
            MenuInventoryTransactionService menuInventoryTransactionService,
            PickupIntervalTimePolicy intervalTimePolicy
    ) {
        this.storePublicQueryService = storePublicQueryService;
        this.menuInventoryTransactionService = menuInventoryTransactionService;
        this.intervalTimePolicy = intervalTimePolicy;
    }

    @Transactional(readOnly = true)
    public PickupAvailability getAvailability(long storeId, LocalDate pickupDate) {
        if (storeId <= 0) {
            throw new IllegalArgumentException("storeId must be positive");
        }
        if (pickupDate == null) {
            throw new IllegalArgumentException("pickupDate must not be null");
        }

        PublicStoreDetail store = storePublicQueryService.getDetail(storeId, null, false);
        requirePickupEnabledOpenStore(store);

        Map<Long, PublicMenu> eligibleMenus = eligibleMenus(
                storePublicQueryService.getMenus(storeId));
        if (eligibleMenus.isEmpty()) {
            return new PickupAvailability(Long.toString(storeId), pickupDate, List.of());
        }

        List<MenuInventoryAvailability> availability = menuInventoryTransactionService
                .findOnlineAvailabilityByDate(new MenuInventoryAvailabilityDateQuery(
                        eligibleMenus.keySet().stream().toList(),
                        pickupDate
                ));

        List<PickupAvailableMenu> unambiguousMenus = availability.stream()
                .filter(item -> item.serviceDate().equals(pickupDate))
                .filter(item -> item.timeZoneId().equals(store.timeZoneId()))
                .filter(item -> eligibleMenus.containsKey(item.menuId()))
                .collect(Collectors.groupingBy(
                        item -> new MenuPickupTime(
                                item.menuId(), item.serviceDate(), item.startTime()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ))
                .values().stream()
                .filter(items -> items.size() == 1)
                .map(List::getFirst)
                .filter(item -> intervalTimePolicy.isOpen(
                        store.timeZoneId(), item.endDate(), item.endTime()))
                .map(item -> toAvailableMenu(item, eligibleMenus.get(item.menuId())))
                .toList();

        TreeMap<LocalTime, List<PickupAvailableMenu>> menusByPickupTime = unambiguousMenus.stream()
                .collect(Collectors.groupingBy(
                        PickupAvailableMenu::startTime,
                        TreeMap::new,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                items -> items.stream()
                                        .sorted(Comparator.comparingLong(item ->
                                                Long.parseLong(item.menuId())))
                                        .toList()
                        )
                ));

        List<PickupAvailabilitySlot> slots = menusByPickupTime.entrySet().stream()
                .map(entry -> new PickupAvailabilitySlot(entry.getKey(), entry.getValue()))
                .toList();
        return new PickupAvailability(Long.toString(storeId), pickupDate, slots);
    }

    private static void requirePickupEnabledOpenStore(PublicStoreDetail store) {
        if (store.operationStatus() != OperationStatus.OPEN || !store.modes().pickupEnabled()) {
            throw new ServiceException(PickupErrorCode.TRANSACTION_NOT_ELIGIBLE);
        }
    }

    private static Map<Long, PublicMenu> eligibleMenus(List<PublicMenu> menus) {
        return menus.stream()
                .filter(menu -> menu.saleStatus() == MenuSellingStatus.SELLING)
                .filter(PublicMenu::pickupEnabled)
                .sorted(Comparator.comparingLong(PickupAvailabilityService::menuId))
                .collect(Collectors.toMap(
                        PickupAvailabilityService::menuId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
    }

    private static long menuId(PublicMenu menu) {
        try {
            long value = Long.parseLong(menu.menuId());
            if (value <= 0) {
                throw new IllegalArgumentException("public menu ID must be positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("public menu ID must be a positive long", exception);
        }
    }

    private static PickupAvailableMenu toAvailableMenu(
            MenuInventoryAvailability availability,
            PublicMenu menu
    ) {
        return new PickupAvailableMenu(
                Long.toString(availability.menuId()),
                menu.name(),
                menu.price(),
                availability.serviceDate(),
                availability.startTime(),
                availability.endTime(),
                PickupAvailabilityStatus.valueOf(availability.availabilityStatus().name()),
                availability.availableOnlineQuantity()
        );
    }

    private record MenuPickupTime(long menuId, LocalDate serviceDate, LocalTime startTime) {
    }
}
