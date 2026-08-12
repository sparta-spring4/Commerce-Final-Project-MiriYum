package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingClosureCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingClosureJobSnapshot;
import com.miriyum.domain.reservation.waiting.entity.WaitingClosureJob;
import com.miriyum.domain.reservation.waiting.entity.WaitingClosureJobItem;
import com.miriyum.domain.reservation.waiting.entity.WaitingClosureJobStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingClosureJobItemRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingClosureJobRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTransitionAuditRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.dao.CannotAcquireLockException;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class WaitingClosureServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T08:00:00Z");
    private static final IdempotencyKey KEY =
            IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000");

    @Mock WaitingStoreAuthorityPort authorityPort;
    @Mock WaitingTeamRepository teamRepository;
    @Mock WaitingClosureJobRepository jobRepository;
    @Mock WaitingClosureJobItemRepository itemRepository;
    @Mock IdempotencyExecutor idempotencyExecutor;
    @Mock WaitingActiveMembershipRepository membershipRepository;
    @Mock WaitingTransitionAuditRepository auditRepository;
    @Mock WaitingStatusEventRepository eventRepository;

    WaitingClosureService service;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new WaitingClosureService(authorityPort, teamRepository, jobRepository,
                itemRepository, idempotencyExecutor, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), membershipRepository,
                auditRepository, eventRepository);
    }

    @Test
    void snapshotsActiveTeamsExactlyOnceAndReturnsAcceptedJob() {
        WaitingTeam first = team(22L, 41L, 1L);
        WaitingTeam second = team(22L, 42L, 2L);
        given(teamRepository.findActiveClosureTargets(22L)).willReturn(List.of(first, second));
        given(jobRepository.saveAndFlush(any())).willAnswer(invocation -> {
            WaitingClosureJob job = invocation.getArgument(0);
            setId(job, 91L);
            return job;
        });
        replayBusinessWork();

        WaitingClosureCommandResult result = service.startClosure(33L, 22L, KEY, 7L);

        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(result.data().jobId()).isEqualTo("91");
        assertThat(result.data().status()).isEqualTo(WaitingClosureJobStatus.PENDING);
        assertThat(result.data().totalTeamCount()).isEqualTo(2L);
        ArgumentCaptor<List<WaitingClosureJobItem>> items = ArgumentCaptor.forClass(List.class);
        then(itemRepository).should().saveAll(items.capture());
        assertThat(items.getValue()).extracting(WaitingClosureJobItem::getWaitingTeamId)
                .containsExactly(41L, 42L);
        assertThat(items.getValue()).extracting(WaitingClosureJobItem::getExpectedVersion)
                .containsExactly(0L, 0L);
        then(teamRepository).should().findActiveClosureTargets(22L);
    }

    @Test
    void zeroActiveTeamsCompleteImmediately() {
        given(teamRepository.findActiveClosureTargets(22L)).willReturn(List.of());
        given(jobRepository.saveAndFlush(any())).willAnswer(invocation -> {
            WaitingClosureJob job = invocation.getArgument(0);
            setId(job, 92L);
            return job;
        });
        replayBusinessWork();

        WaitingClosureCommandResult result = service.startClosure(33L, 22L, KEY, 8L);

        assertThat(result.data().status()).isEqualTo(WaitingClosureJobStatus.COMPLETED);
        assertThat(result.data().totalTeamCount()).isZero();
        assertThat(result.data().completedAt()).isEqualTo(NOW);
        then(itemRepository).should().saveAll(List.of());
    }

    @Test
    void authorityIsCheckedBeforeIdempotencyReplay() {
        given(authorityPort.requireMutation(33L, 22L))
                .willThrow(new ServiceException(ReservationErrorCode.WAITING_CLOSE_JOB_NOT_READY));

        assertThatThrownBy(() -> service.startClosure(33L, 22L, KEY, 7L))
                .isInstanceOf(ServiceException.class);

        then(idempotencyExecutor).shouldHaveNoInteractions();
        then(teamRepository).shouldHaveNoInteractions();
    }

    @Test
    void jobReadIsStoreScopedAndPrivacySafe() {
        given(jobRepository.findByIdAndStoreId(91L, 22L)).willReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getClosureJob(33L, 22L, 91L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITING_CLOSE_JOB_NOT_FOUND);

        then(authorityPort).should().requireRead(33L, 22L);
    }

    @Test
    void closesOneActiveTeamAndAppendsOneAuditAndEvent() {
        WaitingTeam team = team(22L, 41L, 1L);
        WaitingClosureJob job = WaitingClosureJob.create(22L, 7L, 1L, NOW.minusSeconds(10));
        setId(job, 91L);
        WaitingClosureJobItem item = WaitingClosureJobItem.pending(91L, 41L, 0L, NOW.minusSeconds(10));
        setId(item, 101L);
        item.claim("owner", NOW.minusSeconds(5), NOW.plusSeconds(30));
        given(itemRepository.findByIdForUpdate(101L)).willReturn(java.util.Optional.of(item));
        given(jobRepository.findByIdForUpdate(91L)).willReturn(java.util.Optional.of(job));
        given(teamRepository.findByIdForUpdate(41L)).willReturn(java.util.Optional.of(team));
        given(membershipRepository.deleteByWaitingTeamId(41L)).willReturn(1L);
        given(itemRepository.countByWaitingClosureJobIdAndStatus(91L,
                com.miriyum.domain.reservation.waiting.entity.WaitingClosureItemStatus.COMPLETED)).willReturn(1L);

        service.processClaimedItem(new WaitingClosureClaim(101L, "owner", 1L));

        assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CLOSED_BY_STORE);
        assertThat(item.getStatus()).isEqualTo(
                com.miriyum.domain.reservation.waiting.entity.WaitingClosureItemStatus.COMPLETED);
        assertThat(job.getStatus()).isEqualTo(WaitingClosureJobStatus.COMPLETED);
        then(membershipRepository).should().deleteByWaitingTeamId(41L);
        then(auditRepository).should().save(any());
        then(eventRepository).should().save(any());
    }

    @Test
    void terminalTeamIsCompletedAsSkipWithoutDuplicateSideEffects() {
        WaitingTeam team = team(22L, 41L, 1L);
        team.closeByStore(0L, NOW.minusSeconds(5));
        WaitingClosureJob job = WaitingClosureJob.create(22L, 7L, 1L, NOW.minusSeconds(10));
        setId(job, 91L);
        WaitingClosureJobItem item = WaitingClosureJobItem.pending(91L, 41L, 0L, NOW.minusSeconds(10));
        setId(item, 101L);
        item.claim("owner", NOW.minusSeconds(5), NOW.plusSeconds(30));
        given(itemRepository.findByIdForUpdate(101L)).willReturn(java.util.Optional.of(item));
        given(jobRepository.findByIdForUpdate(91L)).willReturn(java.util.Optional.of(job));
        given(teamRepository.findByIdForUpdate(41L)).willReturn(java.util.Optional.of(team));

        service.processClaimedItem(new WaitingClosureClaim(101L, "owner", 1L));

        then(membershipRepository).shouldHaveNoInteractions();
        then(auditRepository).shouldHaveNoInteractions();
        then(eventRepository).shouldHaveNoInteractions();
        assertThat(item.getStatus()).isEqualTo(
                com.miriyum.domain.reservation.waiting.entity.WaitingClosureItemStatus.COMPLETED);
    }

    @Test
    void exhaustedFailureBecomesReconciliationExactlyOnce() {
        WaitingClosureJob job = WaitingClosureJob.create(22L, 7L, 1L, NOW.minusSeconds(10));
        setId(job, 91L);
        WaitingClosureJobItem item = WaitingClosureJobItem.pending(91L, 41L, 0L, NOW.minusSeconds(10));
        setId(item, 101L);
        item.claim("owner", NOW.minusSeconds(3), NOW.plusSeconds(1));
        item.requeue("owner", 1L);
        item.claim("owner", NOW.minusSeconds(2), NOW.plusSeconds(1));
        item.requeue("owner", 2L);
        item.claim("owner", NOW.minusSeconds(1), NOW.plusSeconds(1));
        given(itemRepository.findByIdForUpdate(101L)).willReturn(java.util.Optional.of(item));
        given(jobRepository.findByIdForUpdate(91L)).willReturn(java.util.Optional.of(job));
        given(itemRepository.countByWaitingClosureJobIdAndStatus(eq(91L), any()))
                .willAnswer(invocation -> invocation.getArgument(1)
                        == com.miriyum.domain.reservation.waiting.entity.WaitingClosureItemStatus.RECONCILIATION_REQUIRED
                        ? 1L : 0L);

        service.recordFailure(new WaitingClosureClaim(101L, "owner", 3L), true);

        assertThat(item.getStatus()).isEqualTo(
                com.miriyum.domain.reservation.waiting.entity.WaitingClosureItemStatus.RECONCILIATION_REQUIRED);
        assertThat(job.getReconciliationRequiredTeamCount()).isOne();
        assertThat(job.getStatus()).isEqualTo(WaitingClosureJobStatus.RECONCILIATION_REQUIRED);
    }


    private void replayBusinessWork() {
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<com.miriyum.global.idempotency.BusinessResult<WaitingClosureJobSnapshot>> work =
                    invocation.getArgument(1);
            var result = work.get();
            return new IdempotentOutcome(
                    false, result.httpStatus(), result.responseCode(), result.resourceType(),
                    result.resourceId(), objectMapper.valueToTree(result.data()));
        });
    }

    private static WaitingTeam team(long storeId, long id, long sequence) {
        WaitingTeam team = WaitingTeam.create(
                storeId, 1000L + id, LocalDate.of(2026, 8, 12), 2,
                WaitingSource.REMOTE, sequence, NOW.minusSeconds(60));
        setId(team, id);
        return team;
    }

    private static void setId(Object target, long id) {
        try {
            Field field = target.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
