package com.miriyum.domain.store.search.service;

import com.miriyum.domain.reservation.dto.request.ReservationAvailabilityCondition;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.schedule.entity.OperatingScheduleEntry;
import com.miriyum.domain.store.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.store.schedule.entity.ReservationScheduleEntry;
import com.miriyum.domain.store.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.store.schedule.entity.StoreScheduleState;
import com.miriyum.domain.store.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.store.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.store.schedule.repository.ReservationScheduleVersionRepository;
import com.miriyum.domain.store.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.domain.store.search.dto.PublicDailySchedule;
import com.miriyum.domain.store.search.dto.PublicDailyTimeSlots;
import com.miriyum.domain.store.search.dto.PublicMenu;
import com.miriyum.domain.store.search.dto.PublicStoreDetail;
import com.miriyum.domain.store.search.dto.PublicStoreModes;
import com.miriyum.domain.store.search.dto.PublicTimeRange;
import com.miriyum.domain.store.search.dto.ReservationAvailability;
import com.miriyum.domain.store.search.model.ReservationSearchCondition;
import com.miriyum.domain.store.search.repository.PublicStoreSnapshot;
import com.miriyum.domain.store.search.repository.StorePublicReadRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class StorePublicQueryService {

    private final StorePublicReadRepository publicReadRepository;
    private final StoreScheduleStateRepository stateRepository;
    private final OperatingScheduleVersionRepository operatingRepository;
    private final ReservationScheduleVersionRepository reservationRepository;
    private final ReservationService reservationService;

    public StorePublicQueryService(
            StorePublicReadRepository publicReadRepository,
            StoreScheduleStateRepository stateRepository,
            OperatingScheduleVersionRepository operatingRepository,
            ReservationScheduleVersionRepository reservationRepository,
            ReservationService reservationService
    ) {
        this.publicReadRepository = publicReadRepository;
        this.stateRepository = stateRepository;
        this.operatingRepository = operatingRepository;
        this.reservationRepository = reservationRepository;
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
        StoreSchedules schedules = loadSchedules(storeId);
        PublicStoreSnapshot store = requirePublicStore(storeId);
        return new PublicStoreDetail(
                Long.toString(storeId), store.name(), store.description(),
                store.region(), store.address(), store.timeZoneId(),
                store.storeCategoryCode(), store.tags(),
                store.operationStatus(), store.pickupEligibility(),
                new PublicStoreModes(store.reservationEnabled(), store.menuHoldEnabled(),
                        store.pickupEnabled()),
                schedules.operatingHours(), schedules.reservationTimeSlots(),
                menus.stream().filter(PublicMenu::representative).toList(), availability);
    }

    private StoreSchedules loadSchedules(long storeId) {
        StoreScheduleState state = stateRepository.findById(storeId).orElse(null);
        if (state == null) {
            return StoreSchedules.empty();
        }
        List<PublicDailySchedule> operating = loadOperating(
                state.getActiveOperatingScheduleVersionId());
        List<PublicDailyTimeSlots> reservation = loadReservation(
                state.getActiveReservationScheduleVersionId());
        return new StoreSchedules(operating, reservation);
    }

    private List<PublicDailySchedule> loadOperating(Long versionId) {
        if (versionId == null) {
            return List.of();
        }
        List<OperatingScheduleVersion> versions = operatingRepository
                .findActiveByIdsWithEntries(List.of(versionId), ScheduleVersionStatus.ACTIVE);
        if (versions.size() != 1) {
            return List.of();
        }
        EnumMap<DayOfWeek, List<OperatingScheduleEntry>> byDay =
                new EnumMap<>(DayOfWeek.class);
        versions.getFirst().getEntries().forEach(entry ->
                byDay.computeIfAbsent(entry.getDayOfWeek(), ignored -> new ArrayList<>())
                        .add(entry));
        return byDay.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> new PublicDailySchedule(
                        entry.getKey(), ranges(entry.getValue(), ScheduleIntervalKind.BUSINESS_HOURS),
                        ranges(entry.getValue(), ScheduleIntervalKind.BREAK_TIME)))
                .toList();
    }

    private static List<PublicTimeRange> ranges(
            List<OperatingScheduleEntry> entries,
            ScheduleIntervalKind kind
    ) {
        return entries.stream().filter(entry -> entry.getIntervalKind() == kind)
                .sorted(Comparator.comparingInt(OperatingScheduleEntry::getWeekStartMinute))
                .map(entry -> new PublicTimeRange(entry.getStartTime(), entry.getEndTime()))
                .toList();
    }

    private List<PublicDailyTimeSlots> loadReservation(Long versionId) {
        if (versionId == null) {
            return List.of();
        }
        List<ReservationScheduleVersion> versions = reservationRepository
                .findActiveByIdsWithEntries(List.of(versionId), ScheduleVersionStatus.ACTIVE);
        if (versions.size() != 1) {
            return List.of();
        }
        EnumMap<DayOfWeek, List<ReservationScheduleEntry>> byDay =
                new EnumMap<>(DayOfWeek.class);
        versions.getFirst().getEntries().forEach(entry ->
                byDay.computeIfAbsent(entry.getDayOfWeek(), ignored -> new ArrayList<>())
                        .add(entry));
        return byDay.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> new PublicDailyTimeSlots(entry.getKey(), entry.getValue().stream()
                        .sorted(Comparator.comparingInt(ReservationScheduleEntry::getWeekStartMinute))
                        .map(slot -> new PublicTimeRange(slot.getStartTime(), slot.getEndTime()))
                        .toList()))
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

    private PublicStoreSnapshot requirePublicStore(long storeId) {
        return publicReadRepository.findPublicStore(storeId)
                .orElseThrow(StorePublicQueryService::notFound);
    }

    private static ServiceException notFound() {
        return new ServiceException(StoreErrorCode.STORE_NOT_FOUND);
    }

    private record StoreSchedules(
            List<PublicDailySchedule> operatingHours,
            List<PublicDailyTimeSlots> reservationTimeSlots
    ) {
        private static StoreSchedules empty() {
            return new StoreSchedules(List.of(), List.of());
        }
    }
}
