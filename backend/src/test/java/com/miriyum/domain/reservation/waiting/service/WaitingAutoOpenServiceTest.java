package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.miriyum.domain.reservation.waiting.entity.WaitingAutoOpenJob;
import com.miriyum.domain.reservation.waiting.entity.WaitingAutoOpenJobStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionWindow;
import com.miriyum.domain.reservation.waiting.repository.WaitingAutoOpenJobRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingReceptionWindowRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingSettingRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WaitingAutoOpenServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T23:00:00Z");

    @Mock
    private WaitingAutoOpenJobRepository jobRepository;

    @Mock
    private WaitingReceptionWindowRepository windowRepository;

    @Mock
    private WaitingSettingRepository settingRepository;

    @Mock
    private WaitingOperatingIntervalPort intervalPort;

    private WaitingAutoOpenService service;

    @BeforeEach
    void setUp() {
        service = new WaitingAutoOpenService(
                jobRepository,
                windowRepository,
                settingRepository,
                intervalPort,
                new WaitingAutoOpenFailureClassifier());
    }

    @Test
    void claimsDueJobsWithIncrementedFence() {
        WaitingAutoOpenJob job = job(11L);
        given(jobRepository.findClaimableIds(NOW, 10)).willReturn(List.of(11L));
        given(jobRepository.findAllForUpdateByIdIn(List.of(11L))).willReturn(List.of(job));

        List<WaitingAutoOpenClaim> claims = service.claimDue(
                "worker-a",
                NOW,
                Duration.ofSeconds(30),
                10);

        assertThat(claims).containsExactly(new WaitingAutoOpenClaim(
                11L,
                7L,
                "interval-key",
                LocalDate.of(2026, 8, 17),
                Instant.parse("2026-08-17T00:00:00Z"),
                Instant.parse("2026-08-17T09:00:00Z"),
                3L,
                60,
                "worker-a",
                1L,
                1,
                NOW.plusSeconds(30)));
        assertThat(job.getStatus()).isEqualTo(WaitingAutoOpenJobStatus.PROCESSING);
    }

    @Test
    void transientFailureSchedulesBoundedRetryUnderCurrentFence() {
        WaitingAutoOpenJob job = job(11L);
        job.claim("worker-a", NOW, NOW.plusSeconds(30));
        WaitingAutoOpenClaim claim = claim(job);
        given(jobRepository.findByIdForUpdate(11L)).willReturn(Optional.of(job));

        boolean recorded = service.recordFailure(
                claim,
                NOW.plusSeconds(1),
                new CannotAcquireLockException("deadlock"),
                3,
                Duration.ofSeconds(10),
                Duration.ofSeconds(60));

        assertThat(recorded).isTrue();
        assertThat(job.getStatus()).isEqualTo(WaitingAutoOpenJobStatus.RETRY_WAIT);
        assertThat(job.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(11));
        assertThat(job.getFailureCode()).isEqualTo("DB_LOCK_TRANSIENT");
    }

    @Test
    void maximumAttemptsQuarantineTransientFailure() {
        WaitingAutoOpenJob job = job(11L);
        job.claim("worker-a", NOW, NOW.plusSeconds(30));
        ReflectionTestUtils.setField(job, "attemptCount", 3);
        WaitingAutoOpenClaim claim = claim(job);
        given(jobRepository.findByIdForUpdate(11L)).willReturn(Optional.of(job));

        service.recordFailure(
                claim,
                NOW.plusSeconds(1),
                new CannotAcquireLockException("deadlock"),
                3,
                Duration.ofSeconds(10),
                Duration.ofSeconds(60));

        assertThat(job.getStatus())
                .isEqualTo(WaitingAutoOpenJobStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    void staleFailureReporterCannotRewriteNewOwner() {
        WaitingAutoOpenJob job = job(11L);
        job.claim("worker-a", NOW, NOW.plusSeconds(30));
        WaitingAutoOpenClaim stale = claim(job);
        job.claim("worker-b", NOW.plusSeconds(30), NOW.plusSeconds(60));
        given(jobRepository.findByIdForUpdate(11L)).willReturn(Optional.of(job));

        assertThat(service.recordFailure(
                stale,
                NOW.plusSeconds(31),
                new CannotAcquireLockException("deadlock"),
                3,
                Duration.ofSeconds(10),
                Duration.ofSeconds(60))).isFalse();
        assertThat(job.getLeaseOwner()).isEqualTo("worker-b");
        assertThat(job.getFencingToken()).isEqualTo(2L);
    }

    @Test
    void invalidatesOnlyLimitedUnclaimedStaleJobs() {
        given(jobRepository.invalidateStaleUnclaimed(NOW, 25)).willReturn(4);

        assertThat(service.invalidateStale(NOW, 25)).isEqualTo(4);
    }

    @Test
    void atomicallyOpensCurrentIntervalUnderSettingsCas() {
        WaitingAutoOpenJob job = job(11L);
        job.claim("worker-a", NOW, NOW.plusSeconds(30));
        WaitingAutoOpenClaim claim = claim(job);
        WaitingOperatingInterval interval = new WaitingOperatingInterval(
                7L,
                "interval-key",
                4L,
                LocalDate.of(2026, 8, 17),
                Instant.parse("2026-08-17T00:00:00Z"),
                Instant.parse("2026-08-17T09:00:00Z"),
                "Asia/Seoul");
        given(intervalPort.lockCurrent(
                7L,
                "interval-key",
                interval.startsAt(),
                interval.endsAt(),
                NOW)).willReturn(Optional.of(interval));
        given(jobRepository.findByIdForUpdate(11L)).willReturn(Optional.of(job));
        given(settingRepository.compareAndFenceAutoOpen(7L, 3L, 60, NOW))
                .willReturn(1);
        given(windowRepository.saveAndFlush(any(WaitingReceptionWindow.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        WaitingAutoOpenService.ExecutionResult result = service.execute(claim, NOW);

        assertThat(result).isEqualTo(WaitingAutoOpenService.ExecutionResult.COMPLETED);
        assertThat(job.getStatus()).isEqualTo(WaitingAutoOpenJobStatus.COMPLETED);
        InOrder order = inOrder(intervalPort, jobRepository, settingRepository, windowRepository);
        order.verify(intervalPort).lockCurrent(
                7L,
                "interval-key",
                interval.startsAt(),
                interval.endsAt(),
                NOW);
        order.verify(jobRepository).findByIdForUpdate(11L);
        order.verify(settingRepository).compareAndFenceAutoOpen(7L, 3L, 60, NOW);
        order.verify(windowRepository).saveAndFlush(any(WaitingReceptionWindow.class));
    }

    @Test
    void zeroRowSettingsCasInvalidatesWithoutOpening() {
        WaitingAutoOpenJob job = job(11L);
        job.claim("worker-a", NOW, NOW.plusSeconds(30));
        WaitingAutoOpenClaim claim = claim(job);
        given(intervalPort.lockCurrent(
                7L,
                "interval-key",
                job.getIntervalStartsAt(),
                job.getIntervalEndsAt(),
                NOW)).willReturn(Optional.of(new WaitingOperatingInterval(
                        7L,
                        "interval-key",
                        4L,
                        job.getBusinessDate(),
                        job.getIntervalStartsAt(),
                        job.getIntervalEndsAt(),
                        "Asia/Seoul")));
        given(jobRepository.findByIdForUpdate(11L)).willReturn(Optional.of(job));
        given(settingRepository.compareAndFenceAutoOpen(7L, 3L, 60, NOW))
                .willReturn(0);

        assertThat(service.execute(claim, NOW))
                .isEqualTo(WaitingAutoOpenService.ExecutionResult.INVALIDATED);
        assertThat(job.getStatus()).isEqualTo(WaitingAutoOpenJobStatus.INVALIDATED);
        assertThat(job.getFailureCode()).isEqualTo("STALE_SETTINGS");
        then(windowRepository).shouldHaveNoInteractions();
    }

    @Test
    void staleIntervalInvalidatesBeforeSettingsCas() {
        WaitingAutoOpenJob job = job(11L);
        job.claim("worker-a", NOW, NOW.plusSeconds(30));
        WaitingAutoOpenClaim claim = claim(job);
        given(intervalPort.lockCurrent(
                7L,
                "interval-key",
                job.getIntervalStartsAt(),
                job.getIntervalEndsAt(),
                NOW)).willReturn(Optional.empty());
        given(jobRepository.findByIdForUpdate(11L)).willReturn(Optional.of(job));

        assertThat(service.execute(claim, NOW))
                .isEqualTo(WaitingAutoOpenService.ExecutionResult.INVALIDATED);
        assertThat(job.getFailureCode()).isEqualTo("STALE_INTERVAL");
        then(settingRepository).should(never())
                .compareAndFenceAutoOpen(
                        anyLong(),
                        anyLong(),
                        anyInt(),
                        any(Instant.class));
        then(windowRepository).shouldHaveNoInteractions();
    }

    private WaitingAutoOpenJob job(long id) {
        WaitingAutoOpenJob job = WaitingAutoOpenJob.pending(
                7L,
                "interval-key",
                LocalDate.of(2026, 8, 17),
                Instant.parse("2026-08-17T00:00:00Z"),
                Instant.parse("2026-08-17T09:00:00Z"),
                NOW,
                3L,
                60,
                "0beed06e9d1f34575ddf4ce0a0c942a5cbf13d02074c64dab3ab60d6ee26824c",
                NOW.minusSeconds(60));
        ReflectionTestUtils.setField(job, "id", id);
        return job;
    }

    private WaitingAutoOpenClaim claim(WaitingAutoOpenJob job) {
        return new WaitingAutoOpenClaim(
                job.getId(),
                job.getStoreId(),
                job.getBusinessIntervalKey(),
                job.getBusinessDate(),
                job.getIntervalStartsAt(),
                job.getIntervalEndsAt(),
                job.getExpectedSettingsVersion(),
                job.getExpectedAdvanceOpenMinutes(),
                job.getLeaseOwner(),
                job.getFencingToken(),
                job.getAttemptCount(),
                job.getLeaseUntil());
    }

}
