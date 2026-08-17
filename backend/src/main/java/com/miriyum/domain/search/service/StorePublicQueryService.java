package com.miriyum.domain.search.service;

import com.miriyum.domain.reservation.dto.request.ReservationAvailabilityCondition;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.menu.dto.contract.RepresentativeMenuSnapshot;
import com.miriyum.domain.menu.service.RepresentativeMenuQueryService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.schedule.dto.contract.PublicOperatingDay;
import com.miriyum.domain.schedule.dto.contract.PublicReservationDay;
import com.miriyum.domain.schedule.dto.contract.PublicScheduleTimeRange;
import com.miriyum.domain.schedule.dto.contract.PublicStoreSchedules;
import com.miriyum.domain.schedule.service.StoreScheduleQueryService;
import com.miriyum.domain.search.dto.publicapi.PublicDailySchedule;
import com.miriyum.domain.search.dto.publicapi.PublicDailyTimeSlots;
import com.miriyum.domain.search.dto.publicapi.PublicMenu;
import com.miriyum.domain.search.dto.publicapi.PublicStoreDetail;
import com.miriyum.domain.search.dto.publicapi.PublicStoreModes;
import com.miriyum.domain.search.dto.publicapi.PublicTimeRange;
import com.miriyum.domain.search.dto.publicapi.ReservationAvailability;
import com.miriyum.domain.search.model.ReservationSearchCondition;
import com.miriyum.domain.search.repository.PublicStoreSnapshot;
import com.miriyum.domain.search.repository.StorePublicReadRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.storage.FileStorageOwner;
import com.miriyum.global.storage.FileStoragePurpose;
import com.miriyum.global.storage.service.FileStorageFacade;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorePublicQueryService {

    private final StorePublicReadRepository publicReadRepository;
    private final StoreScheduleQueryService scheduleQueryService;
    private final ReservationService reservationService;
    private final RepresentativeMenuQueryService representativeMenuQueryService;
    private final ObjectProvider<FileStorageFacade> fileStorageFacadeProvider;

    public StorePublicQueryService(
            StorePublicReadRepository publicReadRepository,
            StoreScheduleQueryService scheduleQueryService,
            ReservationService reservationService,
            RepresentativeMenuQueryService representativeMenuQueryService,
            ObjectProvider<FileStorageFacade> fileStorageFacadeProvider
    ) {
        this.publicReadRepository = publicReadRepository;
        this.scheduleQueryService = scheduleQueryService;
        this.reservationService = reservationService;
        this.representativeMenuQueryService = representativeMenuQueryService;
        this.fileStorageFacadeProvider = fileStorageFacadeProvider;
    }

    public List<PublicMenu> getMenus(long storeId) {
        List<PublicMenu> menus = publicReadRepository.findPublicMenus(storeId);
        requirePublicStore(storeId);
        return attachImageUrls(menus);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicStoreDetail getDetail(
            long storeId,
            ReservationSearchCondition condition,
            boolean includesInfants
    ) {
        ReservationAvailability availability = availabilityOf(
                storeId, condition, includesInfants);
        List<PublicMenu> menus = publicReadRepository.findPublicMenus(storeId);
        RepresentativeMenuSnapshot representativeMenus =
                representativeMenuQueryService.getCurrent(storeId);
        PublicStoreSchedules schedules = scheduleQueryService.getPublicSchedules(storeId);
        PublicStoreSnapshot store = requirePublicStore(storeId);
        ReservationAvailability finalAvailability = reconcileLatestStoreState(
                store, availability);
        return new PublicStoreDetail(
                Long.toString(storeId), store.name(), store.description(),
                store.region(), store.address(), store.timeZoneId(),
                store.storeCategoryCode(), store.tags(),
                store.operationStatus(),
                new PublicStoreModes(store.reservationEnabled(), store.menuHoldEnabled(),
                        store.pickupEnabled()),
                operatingHours(schedules.operatingHours()),
                reservationTimeSlots(schedules.reservationTimeSlots()),
                orderedRepresentativeMenus(attachImageUrls(menus), representativeMenus), finalAvailability);
    }

    private List<PublicMenu> attachImageUrls(List<PublicMenu> menus) {
        if (menus.isEmpty()) {
            return menus;
        }
        FileStorageFacade fileStorageFacade = fileStorageFacadeProvider.getIfAvailable();
        if (fileStorageFacade == null) {
            return menus;
        }
        Map<FileStorageOwner, String> urls = fileStorageFacade.findConfirmedPublicUrls(
                menus.stream()
                        .map(menu -> new FileStorageOwner("MENU", Long.parseLong(menu.menuId())))
                        .toList(),
                FileStoragePurpose.MENU_IMAGE);
        return menus.stream()
                .map(menu -> menu.withImageUrl(
                        urls.get(new FileStorageOwner("MENU", Long.parseLong(menu.menuId())))))
                .toList();
    }

    private static List<PublicMenu> orderedRepresentativeMenus(
            List<PublicMenu> menus,
            RepresentativeMenuSnapshot snapshot
    ) {
        Map<String, PublicMenu> menusById = menus.stream().collect(Collectors.toMap(
                PublicMenu::menuId,
                Function.identity()));
        return snapshot.items().stream()
                .map(item -> menusById.get(item.menuId()))
                .filter(java.util.Objects::nonNull)
                .map(StorePublicQueryService::asRepresentative)
                .toList();
    }

    private static PublicMenu asRepresentative(PublicMenu menu) {
        return new PublicMenu(
                menu.menuId(), menu.name(), menu.description(), menu.imageUrl(), menu.price(), true,
                menu.primaryCategoryCode(), menu.secondaryCategoryCodes(),
                menu.localTags(), menu.holdEnabled(),
                menu.pickupEnabled(), menu.saleStatus());
    }

    private static List<PublicDailySchedule> operatingHours(
            List<PublicOperatingDay> days
    ) {
        return days.stream()
                .map(day -> new PublicDailySchedule(
                        day.dayOfWeek(),
                        timeRanges(day.businessHours()),
                        timeRanges(day.breakTimes())))
                .toList();
    }

    private static List<PublicDailyTimeSlots> reservationTimeSlots(
            List<PublicReservationDay> days
    ) {
        return days.stream()
                .map(day -> new PublicDailyTimeSlots(
                        day.dayOfWeek(),
                        timeRanges(day.timeSlots())))
                .toList();
    }

    private static List<PublicTimeRange> timeRanges(
            List<PublicScheduleTimeRange> ranges
    ) {
        return ranges.stream()
                .map(range -> new PublicTimeRange(range.startTime(), range.endTime()))
                .toList();
    }

    private ReservationAvailability availabilityOf(
            long storeId,
            ReservationSearchCondition condition,
            boolean includesInfants
    ) {
        if (condition == null) {
            return ReservationAvailability.NOT_REQUESTED;
        }
        List<ReservationAvailabilityResult> results = reservationService.getAvailabilities(
                List.of(storeId),
                new ReservationAvailabilityCondition(
                        condition.serviceDate(), condition.startTime(), null,
                        condition.partySize(), includesInfants));
        if (results == null || results.size() != 1 || results.getFirst() == null
                || results.getFirst().storeId() != storeId) {
            return ReservationAvailability.UNAVAILABLE;
        }
        return results.getFirst().availability() == ReservationAvailabilityStatus.AVAILABLE
                ? ReservationAvailability.AVAILABLE
                : ReservationAvailability.UNAVAILABLE;
    }

    private static ReservationAvailability reconcileLatestStoreState(
            PublicStoreSnapshot store,
            ReservationAvailability batchAvailability
    ) {
        if (batchAvailability == ReservationAvailability.NOT_REQUESTED) {
            return batchAvailability;
        }
        if (store.operationStatus()
                != com.miriyum.domain.store.enums.OperationStatus.OPEN
                || !store.reservationEnabled()) {
            return ReservationAvailability.UNAVAILABLE;
        }
        return batchAvailability;
    }

    private PublicStoreSnapshot requirePublicStore(long storeId) {
        return publicReadRepository.findPublicStore(storeId)
                .orElseThrow(StorePublicQueryService::notFound);
    }

    private static ServiceException notFound() {
        return new ServiceException(StoreErrorCode.STORE_NOT_FOUND);
    }

}
