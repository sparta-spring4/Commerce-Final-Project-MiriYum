package com.miriyum.domain.reservation.waiting.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WaitingAutoOpenJobTest {

    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    @Test
    void createsPendingJobFromImmutableSettingsAndIntervalSnapshot() {
        WaitingAutoOpenJob job = newJob();

        assertThat(job.getStatus()).isEqualTo(WaitingAutoOpenJobStatus.PENDING);
        assertThat(job.getStoreId()).isEqualTo(7L);
        assertThat(job.getBusinessIntervalKey()).isEqualTo("interval-key");
        assertThat(job.getBusinessDate()).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(job.getExpectedSettingsVersion()).isEqualTo(3L);
        assertThat(job.getExpectedAdvanceOpenMinutes()).isEqualTo(60);
        assertThat(job.getScheduledAt()).isEqualTo(NOW.minus(Duration.ofMinutes(60)));
        assertThat(job.getNextAttemptAt()).isEqualTo(NOW.minus(Duration.ofMinutes(60)));
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getFencingToken()).isZero();
    }

    @Test
    void claimIncrementsAttemptAndFenceAndRejectsStaleOwner() {
        WaitingAutoOpenJob job = newJob();
        Instant leaseUntil = NOW.plusSeconds(30);

        job.claim("worker-a", NOW, leaseUntil);

        assertThat(job.getStatus()).isEqualTo(WaitingAutoOpenJobStatus.PROCESSING);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getFencingToken()).isEqualTo(1L);
        assertThat(job.isOwnedBy("worker-a", 1L, NOW.plusSeconds(1))).isTrue();
        assertThat(job.isOwnedBy("worker-b", 1L, NOW.plusSeconds(1))).isFalse();
        assertThatThrownBy(() -> job.complete("worker-b", 1L, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiredLeaseCanBeReclaimedWithNewFence() {
        WaitingAutoOpenJob job = newJob();
        job.claim("worker-a", NOW, NOW.plusSeconds(30));

        job.claim("worker-b", NOW.plusSeconds(30), NOW.plusSeconds(60));

        assertThat(job.getAttemptCount()).isEqualTo(2);
        assertThat(job.getFencingToken()).isEqualTo(2L);
        assertThat(job.isOwnedBy("worker-a", 1L, NOW.plusSeconds(31))).isFalse();
        assertThat(job.isOwnedBy("worker-b", 2L, NOW.plusSeconds(31))).isTrue();
    }

    @Test
    void retryWaitIsClaimableOnlyWhenDue() {
        WaitingAutoOpenJob job = newJob();
        job.claim("worker-a", NOW, NOW.plusSeconds(30));
        job.retry("worker-a", 1L, NOW.plusSeconds(1), Duration.ofSeconds(10), "DB_TRANSIENT");

        assertThat(job.getStatus()).isEqualTo(WaitingAutoOpenJobStatus.RETRY_WAIT);
        assertThatThrownBy(() -> job.claim(
                "worker-b",
                NOW.plusSeconds(10),
                NOW.plusSeconds(40))).isInstanceOf(IllegalStateException.class);

        job.claim("worker-b", NOW.plusSeconds(11), NOW.plusSeconds(41));

        assertThat(job.getFencingToken()).isEqualTo(2L);
    }

    @Test
    void terminalJobCannotBeClaimedAgain() {
        WaitingAutoOpenJob job = newJob();
        job.claim("worker-a", NOW, NOW.plusSeconds(30));
        job.complete("worker-a", 1L, NOW.plusSeconds(1));

        assertThat(job.getStatus()).isEqualTo(WaitingAutoOpenJobStatus.COMPLETED);
        assertThat(job.getCompletedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThatThrownBy(() -> job.claim(
                "worker-b",
                NOW.plusSeconds(31),
                NOW.plusSeconds(61))).isInstanceOf(IllegalStateException.class);
    }

    private WaitingAutoOpenJob newJob() {
        return WaitingAutoOpenJob.pending(
                7L,
                "interval-key",
                LocalDate.of(2026, 8, 17),
                NOW,
                NOW.plusSeconds(32_400),
                NOW.minus(Duration.ofMinutes(60)),
                3L,
                60,
                "90f3ea942a80d3851e1d27fe484d833190f750c0b00d2a7d6e5da0199821f593",
                NOW.minus(Duration.ofHours(2)));
    }
}
