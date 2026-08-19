package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.repository.WaitingLocationProofSessionRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingQueueSequenceRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTransitionAuditRepository;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.service.StoreTransactionEligibilityService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

class WaitingCreationServiceTest {

    @Test
    void rejectsMissingLocationProofInsideTheIdempotentFirstExecution() {
        WaitingQueueSequenceRepository sequenceRepository = mock(WaitingQueueSequenceRepository.class);
        WaitingTeamRepository teamRepository = mock(WaitingTeamRepository.class);
        WaitingActiveMembershipRepository membershipRepository = mock(WaitingActiveMembershipRepository.class);
        WaitingTransitionAuditRepository auditRepository = mock(WaitingTransitionAuditRepository.class);
        WaitingStatusEventRepository eventRepository = mock(WaitingStatusEventRepository.class);
        WaitingLocationProofSessionRepository proofRepository =
                mock(WaitingLocationProofSessionRepository.class);
        WaitingReceptionGate receptionGate = mock(WaitingReceptionGate.class);
        IdempotencyExecutor idempotencyExecutor = mock(IdempotencyExecutor.class);
        WaitingCreationTransactionExecutor transactionExecutor = mock(WaitingCreationTransactionExecutor.class);
        StoreTransactionEligibilityService storeEligibility = mock(StoreTransactionEligibilityService.class);
        when(transactionExecutor.execute(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(idempotencyExecutor.execute(any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        UUID proofId = UUID.fromString("d276a024-71f5-4f98-9682-89f16df4fbd0");
        when(proofRepository.findByIdForUpdate(proofId)).thenReturn(Optional.empty());
        WaitingCreationService service = new WaitingCreationService(
                sequenceRepository, teamRepository, membershipRepository, auditRepository, eventRepository,
                proofRepository, receptionGate, idempotencyExecutor, transactionExecutor,
                storeEligibility, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC),
                attempt -> 0L, millis -> { });

        assertThatThrownBy(() -> service.createForConsumer(
                100L,
                200L,
                LocalDate.of(2026, 8, 17),
                2,
                WaitingSource.REMOTE,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440001"),
                proofId))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.LOCATION_PROOF_INVALID));

        verify(idempotencyExecutor).execute(any(), any());
        verify(proofRepository).findByIdForUpdate(proofId);
        verifyNoInteractions(storeEligibility, receptionGate, sequenceRepository, teamRepository,
                membershipRepository, auditRepository, eventRepository);
    }

    @Test
    @DisplayName("제재로 대기 기능이 제한된 매장은 신규 대기 관계를 만들지 않는다")
    void rejectsStoreWithRestrictedWaitingFeatureBeforeCreatingMembership() {
        WaitingQueueSequenceRepository sequenceRepository = mock(WaitingQueueSequenceRepository.class);
        WaitingTeamRepository teamRepository = mock(WaitingTeamRepository.class);
        WaitingActiveMembershipRepository membershipRepository = mock(WaitingActiveMembershipRepository.class);
        WaitingTransitionAuditRepository auditRepository = mock(WaitingTransitionAuditRepository.class);
        WaitingStatusEventRepository eventRepository = mock(WaitingStatusEventRepository.class);
        WaitingReceptionGate receptionGate = mock(WaitingReceptionGate.class);
        IdempotencyExecutor idempotencyExecutor = mock(IdempotencyExecutor.class);
        WaitingCreationTransactionExecutor transactionExecutor = mock(WaitingCreationTransactionExecutor.class);
        StoreTransactionEligibilityService storeEligibility = mock(StoreTransactionEligibilityService.class);
        when(transactionExecutor.execute(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(idempotencyExecutor.execute(any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        doThrow(new ServiceException(StoreErrorCode.STORE_FEATURE_RESTRICTED))
                .when(storeEligibility).requireWaitingTransactionEligibility(100L);
        WaitingCreationService service = new WaitingCreationService(
                sequenceRepository, teamRepository, membershipRepository, auditRepository, eventRepository,
                receptionGate, idempotencyExecutor, transactionExecutor, storeEligibility, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC),
                attempt -> 0L, millis -> { });

        assertThatThrownBy(() -> service.create(
                100L, 200L, LocalDate.of(2026, 8, 14), 2, WaitingSource.REMOTE,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440001")))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(StoreErrorCode.STORE_FEATURE_RESTRICTED));

        verify(storeEligibility).requireWaitingTransactionEligibility(100L);
        verify(idempotencyExecutor).execute(any(), any());
        verifyNoInteractions(receptionGate, sequenceRepository, teamRepository, membershipRepository,
                auditRepository, eventRepository);
    }

    @Test
    void retryRechecksStoreAndReceptionEligibilityAtCurrentAttemptTime() {
        Instant requestedAt = Instant.parse("2026-08-17T00:00:00Z");
        Instant firstGateAt = Instant.parse("2026-08-17T00:00:01Z");
        Instant acceptingUntil = Instant.parse("2026-08-17T00:00:02Z");
        WaitingQueueSequenceRepository sequenceRepository = mock(WaitingQueueSequenceRepository.class);
        WaitingTeamRepository teamRepository = mock(WaitingTeamRepository.class);
        WaitingActiveMembershipRepository membershipRepository = mock(WaitingActiveMembershipRepository.class);
        WaitingTransitionAuditRepository auditRepository = mock(WaitingTransitionAuditRepository.class);
        WaitingStatusEventRepository eventRepository = mock(WaitingStatusEventRepository.class);
        WaitingReceptionGate receptionGate = mock(WaitingReceptionGate.class);
        IdempotencyExecutor idempotencyExecutor = mock(IdempotencyExecutor.class);
        WaitingCreationTransactionExecutor transactionExecutor = mock(WaitingCreationTransactionExecutor.class);
        StoreTransactionEligibilityService storeEligibility = mock(StoreTransactionEligibilityService.class);
        Clock clock = mock(Clock.class);
        LocalDate businessDate = LocalDate.of(2026, 8, 17);
        when(clock.instant()).thenReturn(requestedAt, firstGateAt, acceptingUntil);
        when(transactionExecutor.execute(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(idempotencyExecutor.execute(any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        when(membershipRepository.findByConsumerAccountId(200L)).thenReturn(Optional.empty());
        when(sequenceRepository.existsById(any()))
                .thenThrow(new QueryTimeoutException("retry"));
        doNothing().doThrow(new ServiceException(ReservationErrorCode.WAITING_RECEPTION_CLOSED))
                .when(receptionGate).requireOpen(anyLong(), any(LocalDate.class), any(Instant.class));
        WaitingCreationService service = new WaitingCreationService(
                sequenceRepository, teamRepository, membershipRepository, auditRepository, eventRepository,
                receptionGate, idempotencyExecutor, transactionExecutor, storeEligibility,
                new ObjectMapper(), clock, attempt -> 0L, millis -> { });

        assertThatThrownBy(() -> service.create(
                100L, 200L, businessDate, 2, WaitingSource.REMOTE,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440001")))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_RECEPTION_CLOSED));

        verify(storeEligibility, times(2)).requireWaitingTransactionEligibility(100L);
        verify(receptionGate).requireOpen(100L, businessDate, firstGateAt);
        verify(receptionGate).requireOpen(100L, businessDate, acceptingUntil);
        verifyNoInteractions(teamRepository, auditRepository, eventRepository);
    }

    @Test
    @DisplayName("다른 매장에 활성 웨이팅이 있으면 기존 관계를 바꾸지 않고 계정 중복으로 거절한다")
    void rejectsAccountWithActiveWaitingWithoutChangingExistingMembership() {
        WaitingQueueSequenceRepository sequenceRepository = mock(WaitingQueueSequenceRepository.class);
        WaitingTeamRepository teamRepository = mock(WaitingTeamRepository.class);
        WaitingActiveMembershipRepository membershipRepository = mock(WaitingActiveMembershipRepository.class);
        WaitingTransitionAuditRepository auditRepository = mock(WaitingTransitionAuditRepository.class);
        WaitingStatusEventRepository eventRepository = mock(WaitingStatusEventRepository.class);
        WaitingReceptionGate receptionGate = mock(WaitingReceptionGate.class);
        IdempotencyExecutor idempotencyExecutor = mock(IdempotencyExecutor.class);
        WaitingCreationTransactionExecutor transactionExecutor = mock(WaitingCreationTransactionExecutor.class);
        StoreTransactionEligibilityService storeEligibility = mock(StoreTransactionEligibilityService.class);
        when(membershipRepository.findByConsumerAccountId(200L))
                .thenReturn(Optional.of(mock(WaitingActiveMembership.class)));
        when(transactionExecutor.execute(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(idempotencyExecutor.execute(any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        WaitingCreationService service = new WaitingCreationService(
                sequenceRepository, teamRepository, membershipRepository, auditRepository, eventRepository,
                receptionGate,
                idempotencyExecutor, transactionExecutor, storeEligibility, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC),
                attempt -> 0L, millis -> { });

        assertThatThrownBy(() -> service.create(
                100L, 200L, LocalDate.of(2026, 8, 14), 2, WaitingSource.REMOTE,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440001")))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.ACCOUNT_ACTIVE_WAITING_EXISTS));

        verify(receptionGate).requireOpen(
                100L, LocalDate.of(2026, 8, 14), Instant.parse("2026-08-14T00:00:00Z"));
        verify(membershipRepository).findByConsumerAccountId(200L);
        verify(storeEligibility).requireWaitingTransactionEligibility(100L);
        verifyNoMoreInteractions(membershipRepository);
        verifyNoInteractions(sequenceRepository, teamRepository, auditRepository, eventRepository);
    }
}
