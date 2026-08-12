package com.miriyum.domain.schedule.service;

import com.miriyum.domain.schedule.dto.contract.PublicOperatingDay;
import com.miriyum.domain.schedule.dto.contract.PublicReservationDay;
import com.miriyum.domain.schedule.dto.contract.PublicScheduleTimeRange;
import com.miriyum.domain.schedule.dto.contract.PublicStoreSchedules;
import com.miriyum.domain.schedule.entity.OperatingScheduleEntry;
import com.miriyum.domain.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.schedule.entity.ReservationScheduleEntry;
import com.miriyum.domain.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.schedule.entity.StoreScheduleState;
import com.miriyum.domain.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.schedule.repository.ReservationScheduleVersionRepository;
import com.miriyum.domain.schedule.repository.StoreScheduleStateRepository;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 매장의 현재 활성 일정을 외부 도메인용 불변 projection으로 제공한다. */
@Service
@RequiredArgsConstructor
public class StoreScheduleQueryService {

    private final StoreScheduleStateRepository stateRepository;
    private final OperatingScheduleVersionRepository operatingRepository;
    private final ReservationScheduleVersionRepository reservationRepository;

    /**
     * 공개 매장 상세가 사용할 현재 활성 영업·예약 일정을 조회한다.
     *
     * @param storeId 매장 식별자
     * @return 활성 일정이 없거나 불완전하면 해당 목록이 비어 있는 projection
     */
    @Transactional(readOnly = true)
    public PublicStoreSchedules getPublicSchedules(long storeId) {
        StoreScheduleState state = stateRepository.findById(storeId).orElse(null);
        if (state == null) {
            return PublicStoreSchedules.empty();
        }
        return new PublicStoreSchedules(
                loadOperating(state.getActiveOperatingScheduleVersionId()),
                loadReservation(state.getActiveReservationScheduleVersionId()));
    }

    private List<PublicOperatingDay> loadOperating(Long versionId) {
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
                .map(entry -> new PublicOperatingDay(
                        entry.getKey(),
                        operatingRanges(entry.getValue(), ScheduleIntervalKind.BUSINESS_HOURS),
                        operatingRanges(entry.getValue(), ScheduleIntervalKind.BREAK_TIME)))
                .toList();
    }

    private static List<PublicScheduleTimeRange> operatingRanges(
            List<OperatingScheduleEntry> entries,
            ScheduleIntervalKind kind
    ) {
        return entries.stream()
                .filter(entry -> entry.getIntervalKind() == kind)
                .sorted(Comparator.comparingInt(OperatingScheduleEntry::getWeekStartMinute))
                .map(entry -> new PublicScheduleTimeRange(
                        entry.getStartTime(), entry.getEndTime()))
                .toList();
    }

    private List<PublicReservationDay> loadReservation(Long versionId) {
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
                .map(entry -> new PublicReservationDay(
                        entry.getKey(),
                        entry.getValue().stream()
                                .sorted(Comparator.comparingInt(
                                        ReservationScheduleEntry::getWeekStartMinute))
                                .map(slot -> new PublicScheduleTimeRange(
                                        slot.getStartTime(), slot.getEndTime()))
                                .toList()))
                .toList();
    }
}
