package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingQueueSequenceRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTransitionAuditRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WaitingCreationServiceTest {

    @Test
    @DisplayName("다른 매장에 활성 웨이팅이 있으면 기존 관계를 바꾸지 않고 계정 중복으로 거절한다")
    void rejectsAccountWithActiveWaitingWithoutChangingExistingMembership() {
        WaitingQueueSequenceRepository sequenceRepository = mock(WaitingQueueSequenceRepository.class);
        WaitingTeamRepository teamRepository = mock(WaitingTeamRepository.class);
        WaitingActiveMembershipRepository membershipRepository = mock(WaitingActiveMembershipRepository.class);
        WaitingTransitionAuditRepository auditRepository = mock(WaitingTransitionAuditRepository.class);
        WaitingStatusEventRepository eventRepository = mock(WaitingStatusEventRepository.class);
        IdempotencyExecutor idempotencyExecutor = mock(IdempotencyExecutor.class);
        WaitingCreationTransactionExecutor transactionExecutor = mock(WaitingCreationTransactionExecutor.class);
        when(membershipRepository.findByConsumerAccountId(200L))
                .thenReturn(Optional.of(mock(WaitingActiveMembership.class)));
        when(transactionExecutor.execute(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(idempotencyExecutor.execute(any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        WaitingCreationService service = new WaitingCreationService(
                sequenceRepository, teamRepository, membershipRepository, auditRepository, eventRepository,
                idempotencyExecutor, transactionExecutor, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC),
                attempt -> 0L, millis -> { });

        assertThatThrownBy(() -> service.create(
                100L, 200L, LocalDate.of(2026, 8, 14), 2, WaitingSource.REMOTE,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440001")))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.ACCOUNT_ACTIVE_WAITING_EXISTS));

        verify(membershipRepository).findByConsumerAccountId(200L);
        verifyNoMoreInteractions(membershipRepository);
        verifyNoInteractions(sequenceRepository, teamRepository, auditRepository, eventRepository);
    }
}
