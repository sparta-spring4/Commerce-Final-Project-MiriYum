package com.miriyum.domain.reservation.service;

import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoverySourceType.RESERVATION_DEPOSIT_REFUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.reservation.entity.ReservationPaymentRecoveryOutbox;
import com.miriyum.domain.reservation.repository.ReservationPaymentRecoveryOutboxRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationPaymentRecoveryOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";

    private ReservationPaymentRecoveryOutboxRepository repository;
    private ReservationPaymentRecoveryOutboxService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReservationPaymentRecoveryOutboxRepository.class);
        service = new ReservationPaymentRecoveryOutboxService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("terminal 복구 enqueue는 source identity 하나를 저장한다")
    void enqueuesTerminalRecovery() {
        when(repository.findBySourceTypeAndSourceIdForUpdate(
                RESERVATION_DEPOSIT_REFUND, "31")).thenReturn(Optional.empty());

        service.enqueue(RESERVATION_DEPOSIT_REFUND, "31",
                "900000000000000001", "reservation:1:cancelled", KEY);

        var captor = org.mockito.ArgumentCaptor
                .forClass(ReservationPaymentRecoveryOutbox.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus())
                .isEqualTo(ReservationPaymentRecoveryOutbox.Status.PENDING);
        assertThat(captor.getValue().getPaymentId()).isEqualTo("900000000000000001");
    }

    @Test
    @DisplayName("동일 source의 완전 동일 enqueue는 기존 outbox로 수렴한다")
    void replaysMatchingEnqueue() {
        ReservationPaymentRecoveryOutbox existing = outbox();
        when(repository.findBySourceTypeAndSourceIdForUpdate(
                RESERVATION_DEPOSIT_REFUND, "31")).thenReturn(Optional.of(existing));

        service.enqueue(RESERVATION_DEPOSIT_REFUND, "31",
                "900000000000000001", "reservation:1:cancelled", KEY);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("동일 source의 다른 immutable payload는 동시 수정 오류다")
    void rejectsMismatchedReplay() {
        when(repository.findBySourceTypeAndSourceIdForUpdate(
                RESERVATION_DEPOSIT_REFUND, "31")).thenReturn(Optional.of(outbox()));

        assertThatThrownBy(() -> service.enqueue(RESERVATION_DEPOSIT_REFUND, "31",
                "900000000000000001", "reservation:other", KEY))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION));
    }

    @Test
    @DisplayName("due outbox claim은 Payment 공개 등록 명령에 필요한 scalar만 반환한다")
    void claimsDueOutbox() {
        ReservationPaymentRecoveryOutbox outbox = outbox();
        ReflectionTestUtils.setField(outbox, "id", 41L);
        when(repository.findClaimableForUpdate(any(), any())).thenReturn(List.of(outbox));

        List<ReservationPaymentRecoveryOutboxService.Claim> claims =
                service.claimDue("worker-a", 10);

        assertThat(claims).singleElement().satisfies(claim -> {
            assertThat(claim.outboxId()).isEqualTo(41L);
            assertThat(claim.toCommand().sourceType()).isEqualTo(RESERVATION_DEPOSIT_REFUND);
            assertThat(claim.toCommand().sourceId()).isEqualTo("31");
            assertThat(claim.toCommand().paymentId()).isEqualTo("900000000000000001");
        });
        assertThat(outbox.getClaimToken()).isEqualTo(1L);
    }

    private static ReservationPaymentRecoveryOutbox outbox() {
        return ReservationPaymentRecoveryOutbox.pending(
                RESERVATION_DEPOSIT_REFUND,
                "31",
                "900000000000000001",
                "reservation:1:cancelled",
                KEY,
                NOW);
    }
}
