package com.miriyum.domain.schedule.service;

import com.miriyum.domain.schedule.closure.entity.RegularClosureVersion;
import com.miriyum.domain.schedule.closure.entity.TemporaryClosure;
import com.miriyum.domain.schedule.closure.model.TemporaryClosureStatus;
import com.miriyum.domain.schedule.closure.repository.RegularClosureVersionRepository;
import com.miriyum.domain.schedule.closure.repository.TemporaryClosureRepository;
import com.miriyum.domain.schedule.dto.contract.WaitingOperatingIntervalSnapshot;
import com.miriyum.domain.schedule.entity.OperatingScheduleEntry;
import com.miriyum.domain.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.schedule.entity.StoreScheduleState;
import com.miriyum.domain.schedule.model.ScheduleIntervalKind;
import com.miriyum.domain.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.domain.store.dto.contract.StoreWaitingReceptionProfile;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 현재 활성 Store 일정에서 Waiting이 사용할 영업 구간을 공개한다.
 */
@Service
@RequiredArgsConstructor
public class WaitingOperatingIntervalService {

    private static final String KEY_NAMESPACE = "WAITING_OPERATING_INTERVAL";

    private final StoreService storeService;
    private final StoreScheduleStateRepository stateRepository;
    private final OperatingScheduleVersionRepository operatingRepository;
    private final RegularClosureVersionRepository regularClosureRepository;
    private final TemporaryClosureRepository temporaryClosureRepository;

    @Transactional(readOnly = true)
    public List<WaitingOperatingIntervalSnapshot> findWaitingOperatingIntervals(
            Set<Long> storeIds,
            Instant fromInclusive,
            Instant toExclusive
    ) {
        if (storeIds == null || storeIds.isEmpty()
                || fromInclusive == null || toExclusive == null
                || !fromInclusive.isBefore(toExclusive)) {
            return List.of();
        }
        Map<Long, StoreWaitingReceptionProfile> profiles =
                storeService.getWaitingReceptionProfiles(storeIds);
        Set<Long> eligibleStoreIds = profiles.values().stream()
                .filter(StoreWaitingReceptionProfile::waitingReceptionEligible)
                .map(StoreWaitingReceptionProfile::storeId)
                .collect(Collectors.toUnmodifiableSet());
        if (eligibleStoreIds.isEmpty()) {
            return List.of();
        }

        Map<Long, StoreScheduleState> states = stateRepository
                .findAllByStoreIdIn(eligibleStoreIds)
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        StoreScheduleState::getStoreId,
                        Function.identity()));
        Sources sources = loadSources(states.values());
        List<TemporaryClosure> temporaryClosures = temporaryClosureRepository.findOverlapping(
                eligibleStoreIds,
                fromInclusive,
                toExclusive);
        List<WaitingOperatingIntervalSnapshot> result = new ArrayList<>();
        for (long storeId : eligibleStoreIds.stream().sorted().toList()) {
            if (hasActiveTemporaryClosure(temporaryClosures, storeId, fromInclusive)) {
                continue;
            }
            StoreWaitingReceptionProfile profile = profiles.get(storeId);
            StoreScheduleState state = states.get(storeId);
            if (state == null) {
                continue;
            }
            OperatingScheduleVersion version =
                    sources.operatingById().get(state.getActiveOperatingScheduleVersionId());
            if (!matchesProfile(profile, version)) {
                continue;
            }
            Long regularClosureVersionId = state.getActiveRegularClosureVersionId();
            RegularClosureVersion regularClosure = regularClosureVersionId == null
                    ? null
                    : sources.regularClosureById().get(regularClosureVersionId);
            result.addAll(projectRange(
                    profile,
                    version,
                    regularClosure,
                    fromInclusive,
                    toExclusive));
        }
        return result.stream()
                .sorted(Comparator.comparing(WaitingOperatingIntervalSnapshot::startsAt)
                        .thenComparingLong(WaitingOperatingIntervalSnapshot::storeId)
                        .thenComparing(WaitingOperatingIntervalSnapshot::businessIntervalKey))
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Optional<WaitingOperatingIntervalSnapshot> lockCurrentWaitingOperatingInterval(
            long storeId,
            String businessIntervalKey,
            Instant expectedStartsAt,
            Instant expectedEndsAt,
            Instant now
    ) {
        if (businessIntervalKey == null || businessIntervalKey.isBlank()
                || expectedStartsAt == null || expectedEndsAt == null || now == null
                || !expectedStartsAt.isBefore(expectedEndsAt)) {
            return Optional.empty();
        }
        LockedSources sources = loadLockedSources(storeId);
        if (sources == null) {
            return Optional.empty();
        }
        List<TemporaryClosure> temporaryClosures = temporaryClosureRepository.findOverlapping(
                Set.of(storeId),
                expectedStartsAt.minusSeconds(86_400),
                expectedEndsAt.plusSeconds(86_400));
        if (hasActiveTemporaryClosure(temporaryClosures, storeId, now)) {
            return Optional.empty();
        }
        return projectRange(
                sources.profile(),
                sources.operating(),
                sources.regularClosure(),
                expectedStartsAt.minusSeconds(86_400),
                expectedEndsAt.plusSeconds(86_400))
                .stream()
                .filter(interval -> interval.businessIntervalKey().equals(businessIntervalKey))
                .filter(interval -> interval.startsAt().equals(expectedStartsAt))
                .filter(interval -> interval.endsAt().equals(expectedEndsAt))
                .findFirst();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public List<WaitingOperatingIntervalSnapshot> lockCurrentWaitingOperatingIntervals(
            long storeId,
            LocalDate businessDate,
            Instant now
    ) {
        if (businessDate == null || now == null) {
            return List.of();
        }
        LockedSources sources = loadLockedSources(storeId);
        if (sources == null) {
            return List.of();
        }
        ZoneId zone = zone(sources.profile().timeZoneId());
        if (zone == null) {
            return List.of();
        }
        Instant localDayStart = strictInstant(businessDate.atStartOfDay(), zone);
        Instant localSearchEnd = strictInstant(businessDate.plusDays(2).atStartOfDay(), zone);
        if (localDayStart == null || localSearchEnd == null) {
            return List.of();
        }
        List<TemporaryClosure> temporaryClosures = temporaryClosureRepository.findOverlapping(
                Set.of(storeId),
                localDayStart,
                localSearchEnd);
        if (hasActiveTemporaryClosure(temporaryClosures, storeId, now)) {
            return List.of();
        }
        return projectBusinessDate(
                sources.profile(),
                sources.operating(),
                sources.regularClosure(),
                businessDate);
    }

    private LockedSources loadLockedSources(long storeId) {
        StoreWaitingReceptionProfile profile;
        try {
            profile = storeService.inspectWaitingReceptionForUpdate(storeId);
        } catch (ServiceException failure) {
            return null;
        }
        if (!profile.waitingReceptionEligible()) {
            return null;
        }
        StoreScheduleState state = stateRepository.findForUpdateByStoreId(storeId).orElse(null);
        if (state == null || state.getActiveOperatingScheduleVersionId() == null) {
            return null;
        }
        List<OperatingScheduleVersion> versions = operatingRepository.findActiveByIdsWithEntries(
                List.of(state.getActiveOperatingScheduleVersionId()),
                ScheduleVersionStatus.ACTIVE);
        if (versions.size() != 1 || !matchesProfile(profile, versions.getFirst())) {
            return null;
        }
        RegularClosureVersion regularClosure = null;
        if (state.getActiveRegularClosureVersionId() != null) {
            List<RegularClosureVersion> closures = regularClosureRepository
                    .findActiveByIdsWithEntries(
                            List.of(state.getActiveRegularClosureVersionId()),
                            ScheduleVersionStatus.ACTIVE);
            if (closures.size() != 1) {
                return null;
            }
            regularClosure = closures.getFirst();
        }
        return new LockedSources(profile, versions.getFirst(), regularClosure);
    }

    private Sources loadSources(Collection<StoreScheduleState> states) {
        List<Long> operatingIds = states.stream()
                .map(StoreScheduleState::getActiveOperatingScheduleVersionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, OperatingScheduleVersion> operatingById = operatingIds.isEmpty()
                ? Map.of()
                : operatingRepository.findActiveByIdsWithEntries(
                                operatingIds,
                                ScheduleVersionStatus.ACTIVE)
                        .stream()
                        .collect(Collectors.toUnmodifiableMap(
                                OperatingScheduleVersion::getId,
                                Function.identity()));
        List<Long> closureIds = states.stream()
                .map(StoreScheduleState::getActiveRegularClosureVersionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, RegularClosureVersion> regularById = closureIds.isEmpty()
                ? Map.of()
                : regularClosureRepository.findActiveByIdsWithEntries(
                                closureIds,
                                ScheduleVersionStatus.ACTIVE)
                        .stream()
                        .collect(Collectors.toUnmodifiableMap(
                                RegularClosureVersion::getId,
                                Function.identity()));
        return new Sources(operatingById, regularById);
    }

    private List<WaitingOperatingIntervalSnapshot> projectRange(
            StoreWaitingReceptionProfile profile,
            OperatingScheduleVersion version,
            RegularClosureVersion regularClosure,
            Instant fromInclusive,
            Instant toExclusive
    ) {
        ZoneId zone = zone(profile.timeZoneId());
        if (zone == null) {
            return List.of();
        }
        LocalDate firstBusinessDate = fromInclusive.atZone(zone).toLocalDate().minusDays(1);
        LocalDate lastBusinessDate = toExclusive.minusNanos(1).atZone(zone).toLocalDate();
        List<WaitingOperatingIntervalSnapshot> result = new ArrayList<>();
        for (LocalDate date = firstBusinessDate;
                !date.isAfter(lastBusinessDate);
                date = date.plusDays(1)) {
            for (WaitingOperatingIntervalSnapshot interval : projectBusinessDate(
                    profile,
                    version,
                    regularClosure,
                    date)) {
                if (interval.startsAt().isBefore(toExclusive)
                        && fromInclusive.isBefore(interval.endsAt())) {
                    result.add(interval);
                }
            }
        }
        return result;
    }

    private List<WaitingOperatingIntervalSnapshot> projectBusinessDate(
            StoreWaitingReceptionProfile profile,
            OperatingScheduleVersion version,
            RegularClosureVersion regularClosure,
            LocalDate businessDate
    ) {
        if (regularClosure != null && regularClosure.isClosedOn(businessDate)) {
            return List.of();
        }
        ZoneId zone = zone(profile.timeZoneId());
        if (zone == null || !matchesProfile(profile, version)) {
            return List.of();
        }
        List<OperatingScheduleEntry> entries = version.getEntries().stream()
                .filter(entry -> entry.getIntervalKind() == ScheduleIntervalKind.BUSINESS_HOURS)
                .filter(entry -> entry.getDayOfWeek() == businessDate.getDayOfWeek())
                .sorted(Comparator.comparingInt(OperatingScheduleEntry::getWeekStartMinute)
                        .thenComparingInt(OperatingScheduleEntry::getWeekEndMinute)
                        .thenComparing(OperatingScheduleEntry::getStartTime)
                        .thenComparing(OperatingScheduleEntry::getEndTime))
                .toList();
        List<WaitingOperatingIntervalSnapshot> result = new ArrayList<>();
        for (int ordinal = 0; ordinal < entries.size(); ordinal++) {
            OperatingScheduleEntry entry = entries.get(ordinal);
            LocalDateTime localStart = businessDate.atTime(entry.getStartTime());
            LocalDate endDate = entry.isOvernight() ? businessDate.plusDays(1) : businessDate;
            LocalDateTime localEnd = endDate.atTime(entry.getEndTime());
            Instant startsAt = strictInstant(localStart, zone);
            Instant endsAt = strictInstant(localEnd, zone);
            if (startsAt == null || endsAt == null || !startsAt.isBefore(endsAt)) {
                continue;
            }
            result.add(new WaitingOperatingIntervalSnapshot(
                    profile.storeId(),
                    intervalKey(profile.storeId(), version, businessDate, entry, ordinal),
                    version.getVersionNumber(),
                    businessDate,
                    startsAt,
                    endsAt,
                    profile.timeZoneId()));
        }
        return result;
    }

    private static boolean hasActiveTemporaryClosure(
            List<TemporaryClosure> closures,
            long storeId,
            Instant now
    ) {
        return closures.stream()
                .filter(closure -> closure.getStoreId() == storeId)
                .anyMatch(closure -> closure.statusAt(now) == TemporaryClosureStatus.ACTIVE);
    }

    private static boolean matchesProfile(
            StoreWaitingReceptionProfile profile,
            OperatingScheduleVersion version
    ) {
        return profile != null
                && version != null
                && profile.storeId() == version.getStoreId()
                && profile.timeZoneId().equals(version.getTimeZoneId());
    }

    private static String intervalKey(
            long storeId,
            OperatingScheduleVersion version,
            LocalDate businessDate,
            OperatingScheduleEntry entry,
            int ordinal
    ) {
        String canonical = String.join("|",
                KEY_NAMESPACE,
                Long.toString(storeId),
                Long.toString(version.getVersionNumber()),
                businessDate.toString(),
                entry.getDayOfWeek().name(),
                entry.getStartTime().toString(),
                entry.getEndTime().toString(),
                Boolean.toString(entry.isOvernight()),
                Integer.toString(ordinal));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static ZoneId zone(String timeZoneId) {
        try {
            return ZoneId.of(timeZoneId);
        } catch (DateTimeException | NullPointerException failure) {
            return null;
        }
    }

    private static Instant strictInstant(LocalDateTime value, ZoneId zone) {
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(value);
        return offsets.size() == 1 ? value.toInstant(offsets.getFirst()) : null;
    }

    private record Sources(
            Map<Long, OperatingScheduleVersion> operatingById,
            Map<Long, RegularClosureVersion> regularClosureById
    ) {
    }

    private record LockedSources(
            StoreWaitingReceptionProfile profile,
            OperatingScheduleVersion operating,
            RegularClosureVersion regularClosure
    ) {
    }
}
