package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.controller.dto.MenuHoldAvailabilityResponse;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityQuery;
import com.miriyum.domain.reservation.dto.request.ReservationTimeRequest;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionStatus;
import com.miriyum.domain.reservation.dto.response.ResolvedReservationTime;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.store.menu.dto.MenuHoldSelectableMenu;
import com.miriyum.domain.store.menu.service.MenuHoldSelectionQueryService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuHoldAvailabilityQueryService {

    private final MenuHoldSelectionQueryService selectionQueryService;
    private final ReservationService reservationService;
    private final MenuInventoryTransactionService inventoryService;

    public MenuHoldAvailabilityQueryService(MenuHoldSelectionQueryService selectionQueryService,
            ReservationService reservationService,
            MenuInventoryTransactionService inventoryService) {
        this.selectionQueryService = selectionQueryService;
        this.reservationService = reservationService;
        this.inventoryService = inventoryService;
    }

    @Transactional(readOnly = true)
    public MenuHoldAvailabilityResponse findAvailability(long storeId, LocalDate serviceDate,
            LocalTime startTime, ZoneOffset startOffset) {
        List<MenuHoldSelectableMenu> menus = selectionQueryService.findSelectableMenus(storeId);
        List<ReservationTimeResolutionResult> results = reservationService.resolveReservationTimes(
                List.of(storeId), new ReservationTimeRequest(serviceDate, startTime, startOffset));
        if (results == null || results.size() != 1 || results.getFirst().storeId() != storeId) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        ReservationTimeResolutionResult resolution = results.getFirst();
        if (resolution.status() == ReservationTimeResolutionStatus.UNAVAILABLE) {
            throw new ServiceException(ReservationErrorCode.OUTSIDE_RESERVATION_WINDOW);
        }
        ResolvedReservationTime time = resolution.time();
        if (time == null || time.policyStoreId() != storeId
                || !time.serviceDate().equals(serviceDate)) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(time.timeZoneId());
        } catch (RuntimeException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        var start = time.startAt().atZone(zone);
        var end = time.serviceEndAt().atZone(zone);
        if (start.getOffset().getTotalSeconds() != time.startOffsetSeconds()
                || end.getOffset().getTotalSeconds() != time.serviceEndOffsetSeconds()) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        List<MenuHoldAvailabilityResponse.Item> items = List.of();
        if (!menus.isEmpty()) {
            List<MenuInventoryAvailability> availability =
                    inventoryService.findExistingOnlineAvailability(
                            new MenuInventoryAvailabilityQuery(menus.stream()
                                    .map(MenuHoldSelectableMenu::menuId).toList(),
                                    start.toLocalDate(), start.toLocalTime(),
                                    end.toLocalDate(), end.toLocalTime()));
            if (availability == null || availability.stream().anyMatch(java.util.Objects::isNull)) {
                throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            var seenMenuIds = new HashSet<Long>();
            if (availability.stream().map(MenuInventoryAvailability::menuId)
                    .anyMatch(menuId -> !seenMenuIds.add(menuId))) {
                throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            Map<Long, MenuHoldSelectableMenu> menuById = menus.stream().collect(Collectors.toMap(
                    MenuHoldSelectableMenu::menuId, Function.identity()));
            items = availability.stream().map(bucket -> toItem(bucket, menuById, time.timeZoneId(),
                            start.toLocalDateTime(), end.toLocalDateTime()))
                    .sorted(java.util.Comparator.comparingLong(item -> Long.parseLong(item.menuId())))
                    .toList();
        }
        return new MenuHoldAvailabilityResponse(time.serviceDate(), start.toOffsetDateTime(),
                end.toOffsetDateTime(), time.timeZoneId(), items);
    }

    private MenuHoldAvailabilityResponse.Item toItem(MenuInventoryAvailability bucket,
            Map<Long, MenuHoldSelectableMenu> menuById, String timeZoneId,
            LocalDateTime start, LocalDateTime end) {
        MenuHoldSelectableMenu menu = menuById.get(bucket.menuId());
        if (menu == null || !timeZoneId.equals(bucket.timeZoneId())
                || !start.equals(LocalDateTime.of(bucket.serviceDate(), bucket.startTime()))
                || !end.equals(LocalDateTime.of(bucket.endDate(), bucket.endTime()))) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        int availableOnlineQuantity = bucket.availabilityStatus()
                == MenuInventoryAvailability.AvailabilityStatus.SOLD_OUT
                ? 0 : bucket.availableOnlineQuantity();
        return new MenuHoldAvailabilityResponse.Item(Long.toString(menu.menuId()), menu.menuName(),
                menu.unitPrice(), availableOnlineQuantity, bucket.availabilityStatus());
    }
}
