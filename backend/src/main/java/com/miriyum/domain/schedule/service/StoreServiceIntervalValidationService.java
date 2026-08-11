package com.miriyum.domain.schedule.service;

import com.miriyum.domain.schedule.closure.entity.RegularClosureVersion;
import com.miriyum.domain.schedule.closure.entity.TemporaryClosure;
import com.miriyum.domain.schedule.closure.repository.RegularClosureVersionRepository;
import com.miriyum.domain.schedule.closure.repository.TemporaryClosureRepository;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalResult;
import com.miriyum.domain.schedule.entity.*;
import com.miriyum.domain.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.schedule.repository.*;
import com.miriyum.domain.store.dto.contract.StoreServiceProfile;
import com.miriyum.domain.store.service.StoreService;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoreServiceIntervalValidationService {
    private final StoreService storeService;
    private final StoreScheduleStateRepository stateRepository;
    private final OperatingScheduleVersionRepository operatingRepository;
    private final ReservationScheduleVersionRepository reservationRepository;
    private final RegularClosureVersionRepository regularRepository;
    private final TemporaryClosureRepository temporaryRepository;
    private final StoreServiceIntervalPolicy policy = new StoreServiceIntervalPolicy();

    @Transactional(readOnly = true)
    public List<StoreServiceIntervalResult> validateServiceIntervals(List<StoreServiceIntervalRequest> requests) {
        if (requests == null || requests.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("service interval requests are required");
        }
        if (requests.isEmpty()) return List.of();
        Set<Long> ids = requests.stream().map(StoreServiceIntervalRequest::storeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, StoreServiceProfile> stores = storeService.getServiceProfiles(ids);
        Map<Long, StoreScheduleState> states = stateRepository.findAllByStoreIdIn(ids).stream()
                .collect(Collectors.toMap(StoreScheduleState::getStoreId, Function.identity()));
        Map<Long, OperatingScheduleVersion> operating = byId(loadOperating(states));
        Map<Long, ReservationScheduleVersion> reservation = byId(loadReservation(states));
        Map<Long, RegularClosureVersion> regular = byId(loadRegular(states));
        Instant min = requests.stream().map(StoreServiceIntervalRequest::startAt).min(Instant::compareTo).orElseThrow();
        Instant max = requests.stream().map(StoreServiceIntervalRequest::serviceEndAt).max(Instant::compareTo).orElseThrow();
        Map<Long, List<TemporaryClosure>> temporary = temporaryRepository.findOverlapping(ids, min, max).stream()
                .collect(Collectors.groupingBy(TemporaryClosure::getStoreId));

        return requests.stream().map(request -> {
            StoreServiceProfile store = stores.get(request.storeId());
            StoreScheduleState state = states.get(request.storeId());
            boolean accepting = false;
            if (store != null && state != null) {
                OperatingScheduleVersion op = operating.get(state.getActiveOperatingScheduleVersionId());
                ReservationScheduleVersion res = reservation.get(state.getActiveReservationScheduleVersionId());
                RegularClosureVersion reg = regular.get(state.getActiveRegularClosureVersionId());
                boolean consistent = op != null && res != null && reg != null
                        && op.getStoreId().equals(store.storeId())
                        && res.getStoreId().equals(store.storeId())
                        && reg.getStoreId().equals(store.storeId())
                        && Objects.equals(res.getValidatedOperatingVersionId(), op.getId())
                        && store.timeZoneId().equals(op.getTimeZoneId())
                        && store.timeZoneId().equals(res.getTimeZoneId())
                        && store.timeZoneId().equals(reg.getTimeZoneId());
                if (consistent) {
                    accepting = policy.accepts(request, new StoreServiceIntervalPolicy.Sources(
                            store.reservationAccepting(),
                            store.timeZoneId(),
                            op.getEntries().stream().map(OperatingScheduleEntry::toWeeklyInterval).toList(),
                            res.getEntries().stream().map(ReservationScheduleEntry::toWeeklyInterval).toList(),
                            reg, temporary.getOrDefault(store.storeId(), List.of())));
                }
            }
            return StoreServiceIntervalResult.of(request, accepting);
        }).toList();
    }

    private List<OperatingScheduleVersion> loadOperating(Map<Long, StoreScheduleState> states) {
        List<Long> ids = states.values().stream().map(StoreScheduleState::getActiveOperatingScheduleVersionId)
                .filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? List.of() : operatingRepository.findActiveByIdsWithEntries(ids, ScheduleVersionStatus.ACTIVE);
    }
    private List<ReservationScheduleVersion> loadReservation(Map<Long, StoreScheduleState> states) {
        List<Long> ids = states.values().stream().map(StoreScheduleState::getActiveReservationScheduleVersionId)
                .filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? List.of() : reservationRepository.findActiveByIdsWithEntries(ids, ScheduleVersionStatus.ACTIVE);
    }
    private List<RegularClosureVersion> loadRegular(Map<Long, StoreScheduleState> states) {
        List<Long> ids = states.values().stream().map(StoreScheduleState::getActiveRegularClosureVersionId)
                .filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? List.of() : regularRepository.findActiveByIdsWithEntries(ids, ScheduleVersionStatus.ACTIVE);
    }
    private static <T> Map<Long, T> byId(List<T> values) {
        Map<Long, T> result = new HashMap<>();
        for (T value : values) {
            Long id;
            if (value instanceof OperatingScheduleVersion v) id = v.getId();
            else if (value instanceof ReservationScheduleVersion v) id = v.getId();
            else id = ((RegularClosureVersion) value).getId();
            result.put(id, value);
        }
        return result;
    }
}
