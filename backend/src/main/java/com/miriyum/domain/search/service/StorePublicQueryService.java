package com.miriyum.domain.search.service;

import com.miriyum.domain.reservation.dto.request.ReservationAvailabilityCondition;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.service.ReservationService;
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
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class StorePublicQueryService {

    private final StorePublicReadRepository publicReadRepository;
    private final StoreScheduleQueryService scheduleQueryService;
    private final ReservationService reservationService;

    public StorePublicQueryService(
            StorePublicReadRepository publicReadRepository,
            StoreScheduleQueryService scheduleQueryService,
            ReservationService reservationService
    ) {
        this.publicReadRepository = publicReadRepository;
        this.scheduleQueryService = scheduleQueryService;
        this.reservationService = reservationService;
    }

    public List<PublicMenu> getMenus(long storeId) {
        List<PublicMenu> menus = publicReadRepository.findPublicMenus(storeId);
        requirePublicStore(storeId);
        return menus;
    }

    public PublicStoreDetail getDetail(
            long storeId,
            ReservationSearchCondition condition,
            boolean includesInfants
    ) {
        ReservationAvailability availability = availabilityOf(
                storeId, condition, includesInfants);
        List<PublicMenu> menus = publicReadRepository.findPublicMenus(storeId);
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
                menus.stream().filter(PublicMenu::representative).toList(), finalAvailability);
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
