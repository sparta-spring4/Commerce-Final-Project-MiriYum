package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.repository.*;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.*;
import java.time.*;
import tools.jackson.databind.ObjectMapper;

class WaitingCreationFailureClassifierTest {
    @Test
    void retriesOnlyApprovedTechnicalFailuresAndExactSequenceBootstrapCollision() {
        assertThat(WaitingCreationFailureClassifier.isRetryable(
                new RuntimeException(new SQLException("deadlock", "40001", 1213)))).isTrue();
        assertThat(WaitingCreationFailureClassifier.isRetryable(new QueryTimeoutException("timeout"))).isTrue();
        assertThat(WaitingCreationFailureClassifier.isRetryable(new TransactionTimedOutException("timeout"))).isTrue();
        assertThat(WaitingCreationFailureClassifier.isRetryable(new DataIntegrityViolationException(
                "Duplicate entry for key 'waiting_queue_sequences.PRIMARY'"))).isTrue();
        assertThat(WaitingCreationFailureClassifier.isRetryable(new DataIntegrityViolationException(
                "check constraint ck_waiting_teams_party_size"))).isFalse();
        assertThat(WaitingCreationFailureClassifier.isRetryable(new DataIntegrityViolationException(
                "foreign key fk_waiting_teams_store"))).isFalse();
        assertThat(WaitingCreationFailureClassifier.isMembershipConflict(new DataIntegrityViolationException(
                "uk_waiting_active_memberships_store_consumer"))).isFalse();
        assertThat(WaitingCreationFailureClassifier.isMembershipConflict(new DataIntegrityViolationException(
                "uk_waiting_active_memberships_consumer_account"))).isTrue();
    }

    @Test
    void jitterRangesAreBounded() {
        for (int i = 0; i < 100; i++) {
            assertThat(WaitingCreationService.defaultDelayMillis(1)).isBetween(100L, 200L);
            assertThat(WaitingCreationService.defaultDelayMillis(2)).isBetween(300L, 500L);
        }
    }

    @Test
    void interruptedBackoffRestoresInterruptAndMapsToCommon008() {
        WaitingCreationTransactionExecutor transactions = mock(WaitingCreationTransactionExecutor.class);
        when(transactions.execute(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new QueryTimeoutException("timeout"));
        WaitingCreationService service = new WaitingCreationService(
                mock(WaitingQueueSequenceRepository.class), mock(WaitingTeamRepository.class),
                mock(WaitingActiveMembershipRepository.class), mock(WaitingTransitionAuditRepository.class),
                mock(WaitingStatusEventRepository.class), mock(IdempotencyExecutor.class), transactions,
                new ObjectMapper(), Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                attempt -> 0L, millis -> { throw new InterruptedException("stop"); });

        assertThatThrownBy(() -> service.create(1L, 2L, LocalDate.of(2026, 8, 12), 2,
                WaitingSource.REMOTE, IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440001")))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION));
        assertThat(Thread.interrupted()).isTrue();
    }
}
