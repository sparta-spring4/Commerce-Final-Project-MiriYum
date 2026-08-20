package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType.RESERVATION_DEPOSIT_DISPOSITION;

import com.miriyum.domain.payment.dto.PaymentContracts.DispositionFailureClassification;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionResult;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionStatus;
import com.miriyum.domain.reservation.entity.ReservationDepositDispositionObligation;
import com.miriyum.domain.reservation.repository.ReservationDepositDispositionObligationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationDepositDispositionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    private static final Duration QUERY_DELAY = Duration.ofSeconds(15);

    @Test
    void claimsOnlyObligationAndReturnsImmutableScalarWork() {
        ReservationDepositDispositionObligationRepository repository = mock(
                ReservationDepositDispositionObligationRepository.class);
        ReservationDepositDispositionObligation obligation = obligation();
        given(repository.findClaimableForUpdate(NOW, PageRequest.of(0, 1)))
                .willReturn(List.of(obligation));
        ReservationDepositDispositionService service = service(repository);

        List<ReservationDepositDispositionService.Claim> claims =
                service.claimDue("worker-a", 1);

        assertThat(claims).containsExactly(new ReservationDepositDispositionService.Claim(
                501L,
                31L,
                41L,
                "51",
                "reservation-cancel:41:550e8400-e29b-41d4-a716-446655440240",
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                5_000,
                "550e8400-e29b-41d4-a716-446655440239",
                ReservationDepositDispositionObligation.Operation.APPLY,
                "worker-a",
                1L,
                1));
        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositDispositionObligation.Status.PROCESSING);
        assertThat(obligation.getLeaseUntil()).isEqualTo(NOW.plusSeconds(30));
        verify(repository).saveAndFlush(obligation);
    }

    @Test
    void staleClaimCannotRecordCompletedPaymentResult() {
        ReservationDepositDispositionObligationRepository repository = mock(
                ReservationDepositDispositionObligationRepository.class);
        ReservationDepositDispositionObligation obligation = obligation();
        obligation.claim("worker-a", NOW.minusSeconds(60), NOW.minusSeconds(30));
        ReservationDepositDispositionService.Claim stale = claim(obligation, "worker-a", 1L);
        obligation.claim("worker-b", NOW.minusSeconds(20), NOW.plusSeconds(20));
        given(repository.findByIdForUpdate(501L)).willReturn(Optional.of(obligation));
        ReservationDepositDispositionService service = service(repository);

        assertThat(service.recordResult(
                stale, completed(), RETRY_DELAY, QUERY_DELAY, 3)).isFalse();

        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositDispositionObligation.Status.PROCESSING);
        assertThat(obligation.getLeaseOwner()).isEqualTo("worker-b");
        verify(repository, never()).saveAndFlush(obligation);
    }

    @Test
    void completedResultCompletesCurrentFence() {
        ReservationDepositDispositionObligationRepository repository = mock(
                ReservationDepositDispositionObligationRepository.class);
        ReservationDepositDispositionObligation obligation = claimedObligation();
        ReservationDepositDispositionService.Claim claim =
                claim(obligation, "worker-a", 1L);
        given(repository.findByIdForUpdate(501L)).willReturn(Optional.of(obligation));
        ReservationDepositDispositionService service = service(repository);

        assertThat(service.recordResult(
                claim, completed(), RETRY_DELAY, QUERY_DELAY, 3)).isTrue();

        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositDispositionObligation.Status.COMPLETED);
        assertThat(obligation.getCompletedRefundAmountMinor()).isEqualTo(5_000L);
        verify(repository).saveAndFlush(obligation);
    }

    @Test
    void unknownResultSchedulesQueryAndRetryLimitFailsClosed() {
        ReservationDepositDispositionObligationRepository repository = mock(
                ReservationDepositDispositionObligationRepository.class);
        ReservationDepositDispositionObligation obligation = claimedObligation();
        ReservationDepositDispositionService.Claim first = claim(obligation, "worker-a", 1L);
        given(repository.findByIdForUpdate(501L)).willReturn(Optional.of(obligation));
        ReservationPaymentRecoveryOutboxService outbox =
                mock(ReservationPaymentRecoveryOutboxService.class);
        ReservationDepositDispositionService service = service(repository, outbox);

        assertThat(service.recordResult(
                first, unknown(), RETRY_DELAY, QUERY_DELAY, 3)).isFalse();
        assertThat(obligation.getStatus()).isEqualTo(
                ReservationDepositDispositionObligation.Status.RECONCILIATION_REQUIRED);
        assertThat(obligation.getNextOperation())
                .isEqualTo(ReservationDepositDispositionObligation.Operation.QUERY);
        assertThat(obligation.getNextAttemptAt()).isEqualTo(NOW.plus(QUERY_DELAY));

        obligation.claim("worker-b", NOW.plus(QUERY_DELAY), NOW.plusSeconds(60));
        ReservationDepositDispositionService.Claim thirdAttempt =
                claim(obligation, "worker-b", 2L);
        assertThat(service.recordRetryableFailure(
                thirdAttempt, RETRY_DELAY, 2)).isTrue();
        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositDispositionObligation.Status.RECOVERY_REQUIRED);
        verify(outbox).enqueue(
                RESERVATION_DEPOSIT_DISPOSITION,
                "501",
                "51",
                "reservation-cancel:41:550e8400-e29b-41d4-a716-446655440240",
                "550e8400-e29b-41d4-a716-446655440239");
    }

    @Test
    void queryRuntimeFailureStaysInReconciliationAndKeepsQueryOperation() {
        ReservationDepositDispositionObligationRepository repository = mock(
                ReservationDepositDispositionObligationRepository.class);
        ReservationDepositDispositionObligation obligation = queryClaimedObligation();
        ReservationDepositDispositionService.Claim queryClaim =
                claim(obligation, "worker-a", 2L);
        given(repository.findByIdForUpdate(501L)).willReturn(Optional.of(obligation));
        ReservationDepositDispositionService service = service(repository);

        assertThat(service.recordRetryableFailure(
                queryClaim, RETRY_DELAY, 3)).isTrue();

        assertThat(obligation.getStatus()).isEqualTo(
                ReservationDepositDispositionObligation.Status.RECONCILIATION_REQUIRED);
        assertThat(obligation.getNextOperation())
                .isEqualTo(ReservationDepositDispositionObligation.Operation.QUERY);
        assertThat(obligation.getNextAttemptAt()).isEqualTo(NOW.plus(RETRY_DELAY));
    }

    @Test
    void retryableQueryResultStaysInReconciliationAndKeepsQueryOperation() {
        ReservationDepositDispositionObligationRepository repository = mock(
                ReservationDepositDispositionObligationRepository.class);
        ReservationDepositDispositionObligation obligation = queryClaimedObligation();
        ReservationDepositDispositionService.Claim queryClaim =
                claim(obligation, "worker-a", 2L);
        given(repository.findByIdForUpdate(501L)).willReturn(Optional.of(obligation));
        ReservationDepositDispositionService service = service(repository);

        assertThat(service.recordResult(
                queryClaim, retryableFailure(), RETRY_DELAY, QUERY_DELAY, 3))
                .isFalse();

        assertThat(obligation.getStatus()).isEqualTo(
                ReservationDepositDispositionObligation.Status.RECONCILIATION_REQUIRED);
        assertThat(obligation.getNextOperation())
                .isEqualTo(ReservationDepositDispositionObligation.Operation.QUERY);
        assertThat(obligation.getNextAttemptAt()).isEqualTo(NOW.plus(QUERY_DELAY));
    }

    @Test
    void processingQueryResultDoesNotConsumeReconciliationAttempt() {
        ReservationDepositDispositionObligationRepository repository = mock(
                ReservationDepositDispositionObligationRepository.class);
        ReservationDepositDispositionObligation obligation = queryClaimedObligation();
        ReservationDepositDispositionService.Claim queryClaim =
                claim(obligation, "worker-a", 2L);
        given(repository.findByIdForUpdate(501L)).willReturn(Optional.of(obligation));
        ReservationDepositDispositionService service = service(repository);

        assertThat(service.recordResult(
                queryClaim, processing(), RETRY_DELAY, QUERY_DELAY, 2)).isFalse();

        assertThat(obligation.getStatus()).isEqualTo(
                ReservationDepositDispositionObligation.Status.RECONCILIATION_REQUIRED);
        assertThat(obligation.getNextOperation())
                .isEqualTo(ReservationDepositDispositionObligation.Operation.QUERY);
        assertThat(obligation.getAttemptCount()).isEqualTo(1);
        assertThat(obligation.getPaymentDispositionStatus())
                .isEqualTo(DispositionStatus.PROCESSING.name());
    }

    @Test
    void unknownQueryResultAtAttemptLimitRequiresRecovery() {
        ReservationDepositDispositionObligationRepository repository = mock(
                ReservationDepositDispositionObligationRepository.class);
        ReservationDepositDispositionObligation obligation = queryClaimedObligation();
        ReservationDepositDispositionService.Claim queryClaim =
                claim(obligation, "worker-a", 2L);
        given(repository.findByIdForUpdate(501L)).willReturn(Optional.of(obligation));
        ReservationPaymentRecoveryOutboxService outbox =
                mock(ReservationPaymentRecoveryOutboxService.class);
        ReservationDepositDispositionService service = service(repository, outbox);

        assertThat(service.recordResult(
                queryClaim, unknown(), RETRY_DELAY, QUERY_DELAY, 2)).isFalse();

        assertThat(obligation.getStatus())
                .isEqualTo(ReservationDepositDispositionObligation.Status.RECOVERY_REQUIRED);
        assertThat(obligation.getPaymentDispositionStatus())
                .isEqualTo(DispositionStatus.RECONCILIATION_REQUIRED.name());
        verify(outbox).enqueue(
                RESERVATION_DEPOSIT_DISPOSITION,
                "501",
                "51",
                "reservation-cancel:41:550e8400-e29b-41d4-a716-446655440240",
                "550e8400-e29b-41d4-a716-446655440239");
    }

    private static ReservationDepositDispositionService service(
            ReservationDepositDispositionObligationRepository repository
    ) {
        return service(repository, mock(ReservationPaymentRecoveryOutboxService.class));
    }

    private static ReservationDepositDispositionService service(
            ReservationDepositDispositionObligationRepository repository,
            ReservationPaymentRecoveryOutboxService outbox
    ) {
        return new ReservationDepositDispositionService(
                repository,
                outbox,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
    }

    private static ReservationDepositDispositionObligation claimedObligation() {
        ReservationDepositDispositionObligation obligation = obligation();
        obligation.claim("worker-a", NOW.minusSeconds(1), NOW.plusSeconds(30));
        return obligation;
    }

    private static ReservationDepositDispositionObligation queryClaimedObligation() {
        ReservationDepositDispositionObligation obligation = obligation();
        obligation.claim("seed-worker", NOW.minusSeconds(30), NOW.plusSeconds(30));
        obligation.requireReconciliation(
                "seed-worker",
                1L,
                NOW.minusSeconds(29),
                Duration.ZERO,
                unknownSnapshot());
        obligation.claim("worker-a", NOW.minusSeconds(1), NOW.plusSeconds(30));
        return obligation;
    }

    private static ReservationDepositDispositionObligation obligation() {
        ReservationDepositDispositionObligation obligation =
                ReservationDepositDispositionObligation.pending(
                        31L,
                        41L,
                        "51",
                        "reservation-cancel:41:550e8400-e29b-41d4-a716-446655440240",
                        "RESERVATION_CANCELLED",
                        null,
                        2L,
                        "CONSUMER",
                        5_000,
                        "550e8400-e29b-41d4-a716-446655440239",
                        "550e8400-e29b-41d4-a716-446655440240",
                        NOW.minusSeconds(60));
        ReflectionTestUtils.setField(obligation, "id", 501L);
        return obligation;
    }

    private static ReservationDepositDispositionService.Claim claim(
            ReservationDepositDispositionObligation obligation,
            String owner,
            long token
    ) {
        return new ReservationDepositDispositionService.Claim(
                501L,
                31L,
                41L,
                "51",
                obligation.getSourceEventId(),
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                5_000,
                "550e8400-e29b-41d4-a716-446655440239",
                obligation.getNextOperation(),
                owner,
                token,
                obligation.getAttemptCount());
    }

    private static DispositionResult completed() {
        return result(
                DispositionStatus.COMPLETED,
                null,
                5_000L,
                NOW);
    }

    private static DispositionResult unknown() {
        return result(
                DispositionStatus.RECONCILIATION_REQUIRED,
                DispositionFailureClassification.UNKNOWN,
                0L,
                null);
    }

    private static DispositionResult processing() {
        return result(
                DispositionStatus.PROCESSING,
                null,
                0L,
                null);
    }

    private static DispositionResult retryableFailure() {
        return result(
                DispositionStatus.FAILED,
                DispositionFailureClassification.RETRYABLE,
                0L,
                null);
    }

    private static ReservationDepositDispositionObligation.PaymentSnapshot
            unknownSnapshot() {
        return new ReservationDepositDispositionObligation.PaymentSnapshot(
                "550e8400-e29b-41d4-a716-446655440239",
                "71",
                10_001L,
                5_000L,
                5_000L,
                0L,
                5_001L,
                "KRW",
                DispositionStatus.RECONCILIATION_REQUIRED.name(),
                DispositionFailureClassification.UNKNOWN.name(),
                NOW.minusSeconds(40),
                NOW.minusSeconds(30),
                null);
    }

    private static DispositionResult result(
            DispositionStatus status,
            DispositionFailureClassification failure,
            long completedAmount,
            Instant completedAt
    ) {
        return new DispositionResult(
                "550e8400-e29b-41d4-a716-446655440239",
                "51",
                "reservation-cancel:41:550e8400-e29b-41d4-a716-446655440240",
                "RESERVATION_CANCELLED",
                null,
                2L,
                "CONSUMER",
                5_000,
                10_001L,
                5_000L,
                5_000L,
                completedAmount,
                5_001L,
                "KRW",
                "71",
                status,
                failure,
                NOW.minusSeconds(10),
                NOW,
                completedAt);
    }
}
