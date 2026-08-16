package com.miriyum.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.schedule.closure.entity.RegularClosureVersion;
import com.miriyum.domain.schedule.closure.entity.TemporaryClosure;
import com.miriyum.domain.schedule.closure.model.TemporaryClosureReason;
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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.CannotAcquireLockException;

@ExtendWith(MockitoExtension.class)
class WaitingOperatingIntervalServiceTest {

    private static final long STORE_ID = 7L;
    private static final Instant RANGE_START = Instant.parse("2026-08-16T15:00:00Z");
    private static final Instant RANGE_END = Instant.parse("2026-08-18T15:00:00Z");

    @Mock
    private StoreService storeService;

    @Mock
    private StoreScheduleStateRepository stateRepository;

    @Mock
    private OperatingScheduleVersionRepository operatingRepository;

    @Mock
    private RegularClosureVersionRepository regularClosureRepository;

    @Mock
    private TemporaryClosureRepository temporaryClosureRepository;

    private WaitingOperatingIntervalService service;

    @BeforeEach
    void setUp() {
        service = new WaitingOperatingIntervalService(
                storeService,
                stateRepository,
                operatingRepository,
                regularClosureRepository,
                temporaryClosureRepository);
    }

    @Test
    void projectsCurrentBusinessHoursAsHalfOpenUtcInterval() {
        StoreScheduleState state = activeState(31L, null);
        OperatingScheduleVersion version = activeVersion(
                31L,
                3L,
                businessHours(DayOfWeek.MONDAY, 9, 0, 18, 0, false));
        given(storeService.getWaitingReceptionProfiles(Set.of(STORE_ID)))
                .willReturn(Map.of(STORE_ID, eligibleProfile()));
        given(stateRepository.findAllByStoreIdIn(Set.of(STORE_ID)))
                .willReturn(List.of(state));
        given(operatingRepository.findActiveByIdsWithEntries(
                List.of(31L),
                ScheduleVersionStatus.ACTIVE))
                .willReturn(List.of(version));
        given(temporaryClosureRepository.findOverlapping(
                Set.of(STORE_ID),
                RANGE_START,
                RANGE_END))
                .willReturn(List.of());

        List<WaitingOperatingIntervalSnapshot> result =
                service.findWaitingOperatingIntervals(
                        Set.of(STORE_ID),
                        RANGE_START,
                        RANGE_END);

        assertThat(result).singleElement().satisfies(interval -> {
            assertThat(interval.storeId()).isEqualTo(STORE_ID);
            assertThat(interval.operatingScheduleVersion()).isEqualTo(3L);
            assertThat(interval.businessDate()).isEqualTo(LocalDate.of(2026, 8, 17));
            assertThat(interval.startsAt()).isEqualTo(Instant.parse("2026-08-17T00:00:00Z"));
            assertThat(interval.endsAt()).isEqualTo(Instant.parse("2026-08-17T09:00:00Z"));
            assertThat(interval.timeZoneId()).isEqualTo("Asia/Seoul");
            assertThat(interval.businessIntervalKey()).hasSize(64);
        });
    }

    @Test
    void excludesRegularClosures() {
        StoreScheduleState state = activeState(31L, 41L);
        OperatingScheduleVersion version = mock(OperatingScheduleVersion.class);
        given(version.getId()).willReturn(31L);
        given(version.getStoreId()).willReturn(STORE_ID);
        given(version.getTimeZoneId()).willReturn("Asia/Seoul");
        RegularClosureVersion regularClosure = RegularClosureVersion.createDraft(
                STORE_ID,
                1L,
                "Asia/Seoul",
                List.of(DayOfWeek.MONDAY),
                List.of());
        ReflectionTestUtils.setField(regularClosure, "id", 41L);
        regularClosure.activate(RANGE_START, "test activation");
        given(storeService.getWaitingReceptionProfiles(Set.of(STORE_ID)))
                .willReturn(Map.of(STORE_ID, eligibleProfile()));
        given(stateRepository.findAllByStoreIdIn(Set.of(STORE_ID)))
                .willReturn(List.of(state));
        given(operatingRepository.findActiveByIdsWithEntries(
                List.of(31L),
                ScheduleVersionStatus.ACTIVE))
                .willReturn(List.of(version));
        given(regularClosureRepository.findActiveByIdsWithEntries(
                List.of(41L),
                ScheduleVersionStatus.ACTIVE))
                .willReturn(List.of(regularClosure));
        given(temporaryClosureRepository.findOverlapping(
                Set.of(STORE_ID),
                RANGE_START,
                RANGE_END))
                .willReturn(List.of());

        assertThat(service.findWaitingOperatingIntervals(
                Set.of(STORE_ID),
                RANGE_START,
                RANGE_END)).isEmpty();
    }

    @Test
    void excludesIntervalsOverlappedByTemporaryClosure() {
        StoreScheduleState state = activeState(31L, null);
        OperatingScheduleVersion version = mock(OperatingScheduleVersion.class);
        OperatingScheduleEntry entry =
                businessHours(DayOfWeek.MONDAY, 9, 0, 18, 0, false);
        given(version.getId()).willReturn(31L);
        given(version.getStoreId()).willReturn(STORE_ID);
        given(version.getTimeZoneId()).willReturn("Asia/Seoul");
        given(version.getEntries()).willReturn(List.of(entry));
        TemporaryClosure temporaryClosure = TemporaryClosure.create(
                STORE_ID,
                Instant.parse("2026-08-17T03:00:00Z"),
                Instant.parse("2026-08-17T04:00:00Z"),
                "Asia/Seoul",
                TemporaryClosureReason.MAINTENANCE,
                null);
        given(storeService.getWaitingReceptionProfiles(Set.of(STORE_ID)))
                .willReturn(Map.of(STORE_ID, eligibleProfile()));
        given(stateRepository.findAllByStoreIdIn(Set.of(STORE_ID)))
                .willReturn(List.of(state));
        given(operatingRepository.findActiveByIdsWithEntries(
                List.of(31L),
                ScheduleVersionStatus.ACTIVE))
                .willReturn(List.of(version));
        given(temporaryClosureRepository.findOverlapping(
                Set.of(STORE_ID),
                RANGE_START,
                RANGE_END))
                .willReturn(List.of(temporaryClosure));

        assertThat(service.findWaitingOperatingIntervals(
                Set.of(STORE_ID),
                RANGE_START,
                RANGE_END)).isEmpty();
    }

    @Test
    void lockedLookupAcquiresStoreBeforeScheduleStateAndRejectsChangedBoundaries() {
        StoreScheduleState state = activeState(31L, null);
        OperatingScheduleVersion version = activeVersion(
                31L,
                3L,
                businessHours(DayOfWeek.MONDAY, 9, 0, 18, 0, false));
        given(storeService.inspectWaitingReceptionForUpdate(STORE_ID))
                .willReturn(eligibleProfile());
        given(stateRepository.findForUpdateByStoreId(STORE_ID))
                .willReturn(Optional.of(state));
        given(operatingRepository.findActiveByIdsWithEntries(
                List.of(31L),
                ScheduleVersionStatus.ACTIVE))
                .willReturn(List.of(version));
        given(temporaryClosureRepository.findOverlapping(
                Set.of(STORE_ID),
                Instant.parse("2026-08-17T00:00:00Z"),
                Instant.parse("2026-08-17T08:59:59Z")))
                .willReturn(List.of());
        String key = service.findWaitingOperatingIntervals(
                        Set.of(STORE_ID),
                        RANGE_START,
                        RANGE_END)
                .stream()
                .findFirst()
                .map(WaitingOperatingIntervalSnapshot::businessIntervalKey)
                .orElseGet(() -> intervalKeyFromLockedSources(state, version));

        Optional<WaitingOperatingIntervalSnapshot> result =
                service.lockCurrentWaitingOperatingInterval(
                        STORE_ID,
                        key,
                        Instant.parse("2026-08-17T00:00:00Z"),
                        Instant.parse("2026-08-17T08:59:59Z"));

        assertThat(result).isEmpty();
        InOrder order = inOrder(storeService, stateRepository);
        order.verify(storeService).inspectWaitingReceptionForUpdate(STORE_ID);
        order.verify(stateRepository).findForUpdateByStoreId(STORE_ID);
    }

    @Test
    void lockedLookupPropagatesTransientStoreLockFailure() {
        CannotAcquireLockException failure = new CannotAcquireLockException("busy");
        given(storeService.inspectWaitingReceptionForUpdate(STORE_ID)).willThrow(failure);

        assertThatThrownBy(() -> service.lockCurrentWaitingOperatingInterval(
                STORE_ID,
                "interval-key",
                Instant.parse("2026-08-17T00:00:00Z"),
                Instant.parse("2026-08-17T09:00:00Z")))
                .isSameAs(failure);
    }

    private String intervalKeyFromLockedSources(
            StoreScheduleState state,
            OperatingScheduleVersion version
    ) {
        given(storeService.getWaitingReceptionProfiles(Set.of(STORE_ID)))
                .willReturn(Map.of(STORE_ID, eligibleProfile()));
        given(stateRepository.findAllByStoreIdIn(Set.of(STORE_ID)))
                .willReturn(List.of(state));
        given(temporaryClosureRepository.findOverlapping(
                Set.of(STORE_ID),
                RANGE_START,
                RANGE_END))
                .willReturn(List.of());
        return service.findWaitingOperatingIntervals(Set.of(STORE_ID), RANGE_START, RANGE_END)
                .getFirst()
                .businessIntervalKey();
    }

    private StoreWaitingReceptionProfile eligibleProfile() {
        return new StoreWaitingReceptionProfile(STORE_ID, "Asia/Seoul", true);
    }

    private StoreScheduleState activeState(Long operatingVersionId, Long closureVersionId) {
        StoreScheduleState state = mock(StoreScheduleState.class);
        given(state.getStoreId()).willReturn(STORE_ID);
        given(state.getActiveOperatingScheduleVersionId()).willReturn(operatingVersionId);
        given(state.getActiveRegularClosureVersionId()).willReturn(closureVersionId);
        return state;
    }

    private OperatingScheduleVersion activeVersion(
            long id,
            long versionNumber,
            OperatingScheduleEntry... entries
    ) {
        OperatingScheduleVersion version = mock(OperatingScheduleVersion.class);
        given(version.getId()).willReturn(id);
        given(version.getStoreId()).willReturn(STORE_ID);
        given(version.getVersionNumber()).willReturn(versionNumber);
        given(version.getTimeZoneId()).willReturn("Asia/Seoul");
        given(version.getEntries()).willReturn(List.of(entries));
        return version;
    }

    private OperatingScheduleEntry businessHours(
            DayOfWeek day,
            int startHour,
            int startMinute,
            int endHour,
            int endMinute,
            boolean overnight
    ) {
        OperatingScheduleEntry entry = mock(OperatingScheduleEntry.class);
        given(entry.getDayOfWeek()).willReturn(day);
        given(entry.getIntervalKind()).willReturn(ScheduleIntervalKind.BUSINESS_HOURS);
        given(entry.getStartTime()).willReturn(LocalTime.of(startHour, startMinute));
        given(entry.getEndTime()).willReturn(LocalTime.of(endHour, endMinute));
        given(entry.isOvernight()).willReturn(overnight);
        return entry;
    }
}
