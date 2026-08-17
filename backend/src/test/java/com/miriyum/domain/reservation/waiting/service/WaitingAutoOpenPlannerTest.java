package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.reservation.waiting.entity.WaitingAutoOpenJob;
import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionMode;
import com.miriyum.domain.reservation.waiting.entity.WaitingSetting;
import com.miriyum.domain.reservation.waiting.repository.WaitingAutoOpenJobRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingSettingRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class WaitingAutoOpenPlannerTest {

    private static final Instant NOW = Instant.parse("2026-08-16T23:00:00Z");

    @Mock
    private WaitingSettingRepository settingRepository;

    @Mock
    private WaitingAutoOpenJobRepository jobRepository;

    @Mock
    private WaitingOperatingIntervalPort intervalPort;

    private WaitingAutoOpenPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new WaitingAutoOpenPlanner(
                settingRepository,
                jobRepository,
                intervalPort);
    }

    @Test
    void derivesDeterministicNamespacedIdempotencyDigest() {
        assertThat(WaitingAutoOpenIdempotencyKey.from(7L, "interval-key", 3L))
                .isEqualTo("deef14b399495834e9e7e92d1d0a29b8d7b6627a4cb119e8c6584f80b0cede7e");
    }

    @Test
    void plansDueJobFromCurrentAutoSettingAndIntervalSnapshot() {
        WaitingSetting setting = WaitingSetting.create(
                7L,
                true,
                WaitingReceptionMode.AUTO,
                60,
                NOW.minusSeconds(60));
        WaitingOperatingInterval interval = new WaitingOperatingInterval(
                7L,
                "interval-key",
                4L,
                LocalDate.of(2026, 8, 17),
                Instant.parse("2026-08-17T00:00:00Z"),
                Instant.parse("2026-08-17T09:00:00Z"),
                "Asia/Seoul");
        given(settingRepository.findAutoOpenPlanningCandidates(
                eq(0L),
                any(Pageable.class))).willReturn(List.of(setting));
        given(intervalPort.findUpcoming(
                eq(Set.of(7L)),
                eq(NOW),
                eq(NOW.plus(Duration.ofMinutes(240)))))
                .willReturn(List.of(interval));
        given(settingRepository.findByStoreId(7L)).willReturn(Optional.of(setting));
        given(jobRepository.insertPending(any(WaitingAutoOpenJob.class))).willReturn(1);

        int created = planner.plan(NOW, Duration.ofMinutes(60), 10);

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<WaitingAutoOpenJob> saved =
                ArgumentCaptor.forClass(WaitingAutoOpenJob.class);
        then(jobRepository).should().insertPending(saved.capture());
        assertThat(saved.getValue().getScheduledAt())
                .isEqualTo(Instant.parse("2026-08-16T23:00:00Z"));
        assertThat(saved.getValue().getExpectedSettingsVersion()).isEqualTo(1L);
        assertThat(saved.getValue().getExpectedAdvanceOpenMinutes()).isEqualTo(60);
        assertThat(saved.getValue().getIdempotencyKey())
                .isEqualTo("0beed06e9d1f34575ddf4ce0a0c942a5cbf13d02074c64dab3ab60d6ee26824c");
    }

    @Test
    void rearmsExactStaleIntervalSnapshotInsteadOfInsertingDuplicate() {
        WaitingSetting setting = WaitingSetting.create(
                7L,
                true,
                WaitingReceptionMode.AUTO,
                60,
                NOW.minusSeconds(60));
        WaitingOperatingInterval interval = new WaitingOperatingInterval(
                7L,
                "interval-key",
                4L,
                LocalDate.of(2026, 8, 17),
                Instant.parse("2026-08-17T00:00:00Z"),
                Instant.parse("2026-08-17T09:00:00Z"),
                "Asia/Seoul");
        given(settingRepository.findAutoOpenPlanningCandidates(
                eq(0L), any(Pageable.class))).willReturn(List.of(setting));
        given(intervalPort.findUpcoming(any(), any(), any())).willReturn(List.of(interval));
        given(settingRepository.findByStoreId(7L)).willReturn(Optional.of(setting));
        given(jobRepository.rearmInvalidated(any(WaitingAutoOpenJob.class), eq(NOW)))
                .willReturn(1);

        assertThat(planner.plan(NOW, Duration.ofMinutes(60), 10)).isEqualTo(1);

        then(jobRepository).should(never()).insertPending(any());
    }

    @Test
    void changedSettingSnapshotPreventsInsert() {
        WaitingSetting planned = WaitingSetting.create(
                7L,
                true,
                WaitingReceptionMode.AUTO,
                60,
                NOW.minusSeconds(60));
        WaitingSetting changed = WaitingSetting.create(
                7L,
                true,
                WaitingReceptionMode.AUTO,
                30,
                NOW);
        changed.replace(1L, true, WaitingReceptionMode.AUTO, 30, NOW.plusSeconds(1));
        WaitingOperatingInterval interval = new WaitingOperatingInterval(
                7L,
                "interval-key",
                4L,
                LocalDate.of(2026, 8, 17),
                Instant.parse("2026-08-17T00:00:00Z"),
                Instant.parse("2026-08-17T09:00:00Z"),
                "Asia/Seoul");
        given(settingRepository.findAutoOpenPlanningCandidates(
                eq(0L),
                any(Pageable.class))).willReturn(List.of(planned));
        given(intervalPort.findUpcoming(any(), any(), any())).willReturn(List.of(interval));
        given(settingRepository.findByStoreId(7L)).willReturn(Optional.of(changed));

        assertThat(planner.plan(NOW, Duration.ofMinutes(60), 10)).isZero();
        then(jobRepository).should(never()).insertPending(any());
    }

    @Test
    void advancesKeysetCursorAcrossPollsSoLaterStoresAreNotStarved() {
        WaitingSetting first = WaitingSetting.create(
                7L, true, WaitingReceptionMode.AUTO, 60, NOW);
        WaitingSetting later = WaitingSetting.create(
                8L, true, WaitingReceptionMode.AUTO, 60, NOW);
        given(settingRepository.findAutoOpenPlanningCandidates(
                eq(0L), any(Pageable.class))).willReturn(List.of(first));
        given(settingRepository.findAutoOpenPlanningCandidates(
                eq(7L), any(Pageable.class))).willReturn(List.of(later));
        given(intervalPort.findUpcoming(any(), any(), any())).willReturn(List.of());

        planner.plan(NOW, Duration.ofMinutes(60), 1);
        planner.plan(NOW.plusSeconds(1), Duration.ofMinutes(60), 1);

        verify(settingRepository).findAutoOpenPlanningCandidates(
                eq(7L), any(Pageable.class));
    }
}
