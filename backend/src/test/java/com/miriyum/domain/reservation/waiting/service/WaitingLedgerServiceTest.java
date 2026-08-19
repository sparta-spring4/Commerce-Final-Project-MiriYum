package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingActiveTeamImpact;
import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamSnapshot;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingQueueSequence;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingTransitionAudit;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingQueueSequenceRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTransitionAuditRepository;
import com.miriyum.domain.store.dto.storeoperator.ManagedStoreResponse;
import com.miriyum.domain.store.dto.storeoperator.StoreModesRequest;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.enums.VerificationStatus;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class WaitingLedgerServiceTest {

    private static final long OPERATOR_ID = 11L;
    private static final long STORE_ID = 21L;
    private static final long TEAM_ID = 31L;
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-12T03:01:00Z");
    private static final Instant AUDIT_CREATED_AT = Instant.parse("2026-08-12T03:10:00Z");
    private static final IdempotencyCommand COMMAND = new IdempotencyCommand(
            "store-operator",
            OPERATOR_ID,
            "WAITING_TEAM_CALL",
            "550e8400-e29b-41d4-a716-446655440000",
            "a".repeat(64)
    );

    @Mock
    private WaitingStoreAuthorityPort authorityPort;
    @Mock
    private WaitingTeamRepository teamRepository;
    @Mock
    private WaitingQueueSequenceRepository sequenceRepository;
    @Mock
    private WaitingActiveMembershipRepository membershipRepository;
    @Mock
    private WaitingTransitionAuditRepository auditRepository;
    @Mock
    private WaitingStatusEventRepository eventRepository;
    @Mock
    private IdempotencyExecutor idempotencyExecutor;
    @Mock
    private StoreService storeService;

    private ObjectMapper objectMapper;
    private WaitingLedgerService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new WaitingLedgerService(
                authorityPort,
                teamRepository,
                sequenceRepository,
                membershipRepository,
                auditRepository,
                eventRepository,
                idempotencyExecutor,
                objectMapper,
                Clock.fixed(AUDIT_CREATED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("조회 권한 실패는 웨이팅 팀 조회보다 먼저 전파된다")
    void readAuthorityFailurePrecedesWaitingLookup() {
        ServiceException denied = new ServiceException(StoreErrorCode.ACCESS_DENIED);
        given(authorityPort.requireRead(OPERATOR_ID, STORE_ID)).willThrow(denied);

        assertThatThrownBy(() -> service.getTeam(OPERATOR_ID, STORE_ID, TEAM_ID))
                .isSameAs(denied);

        verifyNoInteractions(teamRepository);
    }

    @Test
    @DisplayName("재생 경로도 변경 권한을 먼저 확인하고 웨이팅 팀 행은 다시 조회하지 않는다")
    void replayRechecksMutationAuthorityBeforeIdempotencyWithoutWaitingLookup() {
        WaitingTeamSnapshot first = snapshot(WaitingTeamStatus.CALLED, 1L);
        given(authorityPort.requireMutation(OPERATOR_ID, STORE_ID))
                .willReturn(new WaitingStoreAuthority(STORE_ID, ZoneId.of("Asia/Seoul")));
        given(idempotencyExecutor.execute(any(), any())).willReturn(new IdempotentOutcome(
                true,
                200,
                "SUCCESS",
                "WAITING_TEAM",
                Long.toString(TEAM_ID),
                objectMapper.valueToTree(first)
        ));

        WaitingTeamSnapshot replay = service.call(
                OPERATOR_ID, STORE_ID, TEAM_ID, 0L, COMMAND, OCCURRED_AT).data();

        InOrder order = inOrder(authorityPort, idempotencyExecutor);
        order.verify(authorityPort).requireMutation(OPERATOR_ID, STORE_ID);
        order.verify(idempotencyExecutor).execute(any(), any());
        verifyNoInteractions(teamRepository);
        assertThat(replay).isEqualTo(first);
    }

    @Test
    @DisplayName("다른 매장에 속한 팀은 존재를 숨기는 WAITING_003으로 거절한다")
    void otherStoreTeamIsPrivacySafeNotFound() {
        given(authorityPort.requireRead(OPERATOR_ID, STORE_ID))
                .willReturn(new WaitingStoreAuthority(STORE_ID, ZoneId.of("Asia/Seoul")));
        given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(
                team(TEAM_ID, 999L, WaitingTeamStatus.WAITING)));

        assertThatThrownBy(() -> service.getTeam(OPERATOR_ID, STORE_ID, TEAM_ID))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_TEAM_NOT_FOUND));
    }

    @Test
    @DisplayName("stale version은 FIFO 판정보다 먼저 WAITING_005로 거절한다")
    void staleVersionPrecedesFifoValidation() {
        WaitingTeam target = team(TEAM_ID, STORE_ID, WaitingTeamStatus.WAITING);
        given(authorityPort.requireMutation(OPERATOR_ID, STORE_ID))
                .willReturn(new WaitingStoreAuthority(STORE_ID, ZoneId.of("Asia/Seoul")));
        given(teamRepository.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(target));
        executeBusinessWork();

        assertThatThrownBy(() -> service.call(
                OPERATOR_ID,
                STORE_ID,
                TEAM_ID,
                1L,
                command("WAITING_TEAM_CALL", "b".repeat(64)),
                OCCURRED_AT
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ReservationErrorCode.WAITING_VERSION_CONFLICT));

        then(teamRepository).should(never()).findFifoHead(
                org.mockito.ArgumentMatchers.anyLong(), any(), any());
        verifyNoInteractions(auditRepository, eventRepository);
    }

    @Test
    @DisplayName("WAITING 상태의 비선두 팀 호출은 WAITING_007로 거절한다")
    void callRejectsNonFifoHead() {
        WaitingTeam target = team(TEAM_ID, STORE_ID, WaitingTeamStatus.WAITING);
        WaitingTeam head = team(TEAM_ID - 1, STORE_ID, WaitingTeamStatus.WAITING);
        given(authorityPort.requireMutation(OPERATOR_ID, STORE_ID))
                .willReturn(new WaitingStoreAuthority(STORE_ID, ZoneId.of("Asia/Seoul")));
        given(teamRepository.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(target));
        allowCallWindow(target);
        given(teamRepository.findFifoHead(
                STORE_ID, target.getBusinessDate(), WaitingTeamStatus.WAITING))
                .willReturn(Optional.of(head));
        executeBusinessWork();

        assertThatThrownBy(() -> service.call(
                OPERATOR_ID, STORE_ID, TEAM_ID, 0L, COMMAND, OCCURRED_AT))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_NOT_FIFO_HEAD));

        assertThat(target.getStatus()).isEqualTo(WaitingTeamStatus.WAITING);
        verifyNoInteractions(auditRepository, eventRepository);
    }

    @Test
    @DisplayName("FIFO 선두 호출은 전이하고 principal·명령 범위의 고유 감사 command를 남긴다")
    void callTransitionsFifoHeadWithScopedAuditCommand() {
        WaitingTeam target = team(TEAM_ID, STORE_ID, WaitingTeamStatus.WAITING);
        given(authorityPort.requireMutation(OPERATOR_ID, STORE_ID))
                .willReturn(new WaitingStoreAuthority(STORE_ID, ZoneId.of("Asia/Seoul")));
        given(teamRepository.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(target));
        allowCallWindow(target);
        given(teamRepository.findFifoHead(
                STORE_ID, target.getBusinessDate(), WaitingTeamStatus.WAITING))
                .willReturn(Optional.of(target));
        executeBusinessWork();

        WaitingCommandResult result = service.call(
                OPERATOR_ID, STORE_ID, TEAM_ID, 0L, COMMAND, OCCURRED_AT);

        ArgumentCaptor<WaitingTransitionAudit> audit =
                ArgumentCaptor.forClass(WaitingTransitionAudit.class);
        then(auditRepository).should().save(audit.capture());
        assertThat(result.data().status()).isEqualTo(WaitingTeamStatus.CALLED);
        assertThat(result.data().version()).isEqualTo(1L);
        assertThat(ReflectionTestUtils.getField(audit.getValue(), "commandId"))
                .isEqualTo("store-operator:11:WAITING_TEAM_CALL:"
                        + "550e8400-e29b-41d4-a716-446655440000");
        verifyNoInteractions(membershipRepository);
        then(eventRepository).should().save(any());
    }

    @Test
    @DisplayName("기존 CALLED 흐름이 있으면 queue 잠금 안에서 다음 팀 호출을 WAITING_007로 거절한다")
    void unresolvedCalledTeamBlocksNextCallInsideQueueLock() {
        WaitingTeam target = team(TEAM_ID, STORE_ID, WaitingTeamStatus.WAITING);
        given(authorityPort.requireMutation(OPERATOR_ID, STORE_ID))
                .willReturn(new WaitingStoreAuthority(STORE_ID, ZoneId.of("Asia/Seoul")));
        given(teamRepository.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(target));
        given(sequenceRepository.findByStoreIdAndBusinessDateForUpdate(
                STORE_ID, target.getBusinessDate()))
                .willReturn(Optional.of(WaitingQueueSequence.create(
                        STORE_ID, target.getBusinessDate())));
        given(teamRepository.existsByStoreIdAndBusinessDateAndStatus(
                STORE_ID, target.getBusinessDate(), WaitingTeamStatus.CALLED))
                .willReturn(true);
        executeBusinessWork();

        assertThatThrownBy(() -> service.call(
                OPERATOR_ID, STORE_ID, TEAM_ID, 0L, COMMAND, OCCURRED_AT))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_NOT_FIFO_HEAD));

        InOrder order = inOrder(teamRepository, sequenceRepository);
        order.verify(teamRepository).findByIdForUpdate(TEAM_ID);
        order.verify(sequenceRepository).findByStoreIdAndBusinessDateForUpdate(
                STORE_ID, target.getBusinessDate());
        order.verify(teamRepository).existsByStoreIdAndBusinessDateAndStatus(
                STORE_ID, target.getBusinessDate(), WaitingTeamStatus.CALLED);
        then(teamRepository).should(never()).findFifoHead(
                org.mockito.ArgumentMatchers.anyLong(), any(), any());
        assertThat(target.getStatus()).isEqualTo(WaitingTeamStatus.WAITING);
        verifyNoInteractions(auditRepository, eventRepository);
    }

    @Test
    @DisplayName("CALLED 팀 도착은 membership을 유지하고 ARRIVED snapshot을 반환한다")
    void arriveTransitionsCalledTeamWithoutRemovingMembership() {
        WaitingTeam target = team(TEAM_ID, STORE_ID, WaitingTeamStatus.CALLED);
        given(authorityPort.requireMutation(OPERATOR_ID, STORE_ID))
                .willReturn(new WaitingStoreAuthority(STORE_ID, ZoneId.of("Asia/Seoul")));
        given(teamRepository.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(target));
        executeBusinessWork();

        WaitingCommandResult result = service.arrive(
                OPERATOR_ID,
                STORE_ID,
                TEAM_ID,
                1L,
                command("WAITING_TEAM_ARRIVE", "c".repeat(64)),
                OCCURRED_AT.plusSeconds(60)
        );

        assertThat(result.data().status()).isEqualTo(WaitingTeamStatus.ARRIVED);
        assertThat(result.data().version()).isEqualTo(2L);
        verifyNoInteractions(membershipRepository);
        then(auditRepository).should().save(any());
        then(eventRepository).should().save(any());
    }

    @Test
    @DisplayName("ARRIVED 팀 입장은 membership을 제거하고 CHECKED_IN snapshot을 반환한다")
    void checkInRemovesMembershipAndReturnsTerminalSnapshot() {
        WaitingTeam target = team(TEAM_ID, STORE_ID, WaitingTeamStatus.ARRIVED);
        given(authorityPort.requireMutation(OPERATOR_ID, STORE_ID))
                .willReturn(new WaitingStoreAuthority(STORE_ID, ZoneId.of("Asia/Seoul")));
        given(teamRepository.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(target));
        given(membershipRepository.deleteByWaitingTeamId(TEAM_ID)).willReturn(2L);
        executeBusinessWork();

        WaitingCommandResult result = service.checkIn(
                OPERATOR_ID,
                STORE_ID,
                TEAM_ID,
                2L,
                command("WAITING_TEAM_CHECK_IN", "d".repeat(64)),
                OCCURRED_AT.plusSeconds(120)
        );

        assertThat(result.data().status()).isEqualTo(WaitingTeamStatus.CHECKED_IN);
        assertThat(result.data().version()).isEqualTo(3L);
        then(membershipRepository).should().deleteByWaitingTeamId(TEAM_ID);
    }

    @Test
    @DisplayName("활성 팀 취소는 membership을 제거하고 CANCELLED snapshot을 반환한다")
    void cancelRemovesMembershipAndReturnsTerminalSnapshot() {
        WaitingTeam target = team(TEAM_ID, STORE_ID, WaitingTeamStatus.WAITING);
        given(authorityPort.requireMutation(OPERATOR_ID, STORE_ID))
                .willReturn(new WaitingStoreAuthority(STORE_ID, ZoneId.of("Asia/Seoul")));
        given(teamRepository.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(target));
        given(membershipRepository.deleteByWaitingTeamId(TEAM_ID)).willReturn(1L);
        executeBusinessWork();

        WaitingCommandResult result = service.cancel(
                OPERATOR_ID,
                STORE_ID,
                TEAM_ID,
                0L,
                command("WAITING_TEAM_CANCEL", "e".repeat(64)),
                OCCURRED_AT
        );

        assertThat(result.data().status()).isEqualTo(WaitingTeamStatus.CANCELLED);
        assertThat(result.data().version()).isEqualTo(1L);
        then(membershipRepository).should().deleteByWaitingTeamId(TEAM_ID);
    }

    @Test
    @DisplayName("예약 전환 중인 팀 취소는 membership을 한 번 제거하고 하나의 종결 기록을 남긴다")
    void cancelConvertingTeamRemovesMembershipAndAppendsOneTerminalTransition() {
        WaitingTeam target = team(TEAM_ID, STORE_ID, WaitingTeamStatus.RESERVATION_CONVERTING);
        given(authorityPort.requireMutation(OPERATOR_ID, STORE_ID))
                .willReturn(new WaitingStoreAuthority(STORE_ID, ZoneId.of("Asia/Seoul")));
        given(teamRepository.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(target));
        given(membershipRepository.deleteByWaitingTeamId(TEAM_ID)).willReturn(1L);
        executeBusinessWork();

        WaitingCommandResult result = service.cancel(
                OPERATOR_ID,
                STORE_ID,
                TEAM_ID,
                0L,
                command("WAITING_TEAM_CANCEL", "a".repeat(64)),
                OCCURRED_AT
        );

        assertThat(result.data().status()).isEqualTo(WaitingTeamStatus.CANCELLED);
        assertThat(result.data().version()).isEqualTo(1L);
        then(membershipRepository).should().deleteByWaitingTeamId(TEAM_ID);
        then(auditRepository).should().save(any());
        then(eventRepository).should().save(any());
    }

    @Test
    @DisplayName("종결 전이에서 활성 membership이 없으면 WAITING_008로 거절한다")
    void terminalTransitionRejectsMissingActiveMembership() {
        WaitingTeam target = team(TEAM_ID, STORE_ID, WaitingTeamStatus.WAITING);
        given(authorityPort.requireMutation(OPERATOR_ID, STORE_ID))
                .willReturn(new WaitingStoreAuthority(STORE_ID, ZoneId.of("Asia/Seoul")));
        given(teamRepository.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(target));
        given(membershipRepository.deleteByWaitingTeamId(TEAM_ID)).willReturn(0L);
        executeBusinessWork();

        assertThatThrownBy(() -> service.cancel(
                OPERATOR_ID,
                STORE_ID,
                TEAM_ID,
                0L,
                command("WAITING_TEAM_CANCEL", "f".repeat(64)),
                OCCURRED_AT
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(
                        ReservationErrorCode.WAITING_ACTIVE_MEMBERSHIP_CONFLICT));

        verifyNoInteractions(auditRepository, eventRepository);
    }

    @Test
    @DisplayName("WAITING이 아닌 팀 호출은 FIFO 조회 전 WAITING_006으로 거절한다")
    void callRejectsInvalidTransitionBeforeFifoLookup() {
        WaitingTeam target = team(TEAM_ID, STORE_ID, WaitingTeamStatus.CALLED);
        given(authorityPort.requireMutation(OPERATOR_ID, STORE_ID))
                .willReturn(new WaitingStoreAuthority(STORE_ID, ZoneId.of("Asia/Seoul")));
        given(teamRepository.findByIdForUpdate(TEAM_ID)).willReturn(Optional.of(target));
        executeBusinessWork();

        assertThatThrownBy(() -> service.call(
                OPERATOR_ID,
                STORE_ID,
                TEAM_ID,
                1L,
                command("WAITING_TEAM_CALL", "1".repeat(64)),
                OCCURRED_AT
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(
                        ReservationErrorCode.WAITING_INVALID_TRANSITION));

        then(teamRepository).should(never()).findFifoHead(
                org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    @DisplayName("활성 영향은 WAITING CALLED ARRIVED RESERVATION_CONVERTING 상태를 집계한다")
    void activeImpactCountsOnlyActiveStatuses() {
        given(authorityPort.requireRead(OPERATOR_ID, STORE_ID))
                .willReturn(new WaitingStoreAuthority(STORE_ID, ZoneId.of("Asia/Seoul")));
        given(teamRepository.countByStoreIdAndStatusIn(any(Long.class), any()))
                .willReturn(7L);

        WaitingActiveTeamImpact impact = service.inspectActiveTeams(OPERATOR_ID, STORE_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<WaitingTeamStatus>> statuses =
                ArgumentCaptor.forClass(Collection.class);
        then(teamRepository).should().countByStoreIdAndStatusIn(
                org.mockito.ArgumentMatchers.eq(STORE_ID), statuses.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(
                WaitingTeamStatus.WAITING,
                WaitingTeamStatus.CALLED,
                WaitingTeamStatus.ARRIVED,
                WaitingTeamStatus.RESERVATION_CONVERTING
        );
        assertThat(impact).isEqualTo(new WaitingActiveTeamImpact(STORE_ID, 7L));
    }

    @Test
    @DisplayName("조회 권한은 APPROVED CLOSED 매장도 허용하고 DTO 시간대를 반환한다")
    void readAuthorityAcceptsApprovedClosedStore() {
        given(storeService.getManagedStore(OPERATOR_ID, STORE_ID))
                .willReturn(managedStore(VerificationStatus.APPROVED, OperationStatus.CLOSED));
        StoreServiceWaitingAuthorityAdapter adapter =
                new StoreServiceWaitingAuthorityAdapter(storeService);

        WaitingStoreAuthority authority = adapter.requireRead(OPERATOR_ID, STORE_ID);

        assertThat(authority).isEqualTo(
                new WaitingStoreAuthority(STORE_ID, ZoneId.of("Asia/Seoul")));
    }

    @Test
    @DisplayName("변경 권한은 APPROVED 임시휴업 매장을 허용한다")
    void mutationAuthorityAcceptsApprovedTemporarilyClosedStore() {
        given(storeService.getManagedStore(OPERATOR_ID, STORE_ID)).willReturn(
                managedStore(VerificationStatus.APPROVED, OperationStatus.TEMPORARILY_CLOSED));
        StoreServiceWaitingAuthorityAdapter adapter =
                new StoreServiceWaitingAuthorityAdapter(storeService);

        assertThat(adapter.requireMutation(OPERATOR_ID, STORE_ID).storeId())
                .isEqualTo(STORE_ID);
    }

    @Test
    @DisplayName("변경 권한은 CLOSED 매장을 기존 STORE_005로 거절한다")
    void mutationAuthorityRejectsClosedStore() {
        given(storeService.getManagedStore(OPERATOR_ID, STORE_ID))
                .willReturn(managedStore(VerificationStatus.APPROVED, OperationStatus.CLOSED));
        StoreServiceWaitingAuthorityAdapter adapter =
                new StoreServiceWaitingAuthorityAdapter(storeService);

        assertThatThrownBy(() -> adapter.requireMutation(OPERATOR_ID, STORE_ID))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT));
    }

    @Test
    @DisplayName("확인할 수 없는 운영 상태는 조회와 변경 모두 STORE_005로 닫힌다")
    void authorityFailsClosedForMissingOperationStatus() {
        given(storeService.getManagedStore(OPERATOR_ID, STORE_ID))
                .willReturn(managedStore(VerificationStatus.APPROVED, null));
        StoreServiceWaitingAuthorityAdapter adapter =
                new StoreServiceWaitingAuthorityAdapter(storeService);

        assertThatThrownBy(() -> adapter.requireRead(OPERATOR_ID, STORE_ID))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT));
        assertThatThrownBy(() -> adapter.requireMutation(OPERATOR_ID, STORE_ID))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT));
    }

    @Test
    @DisplayName("APPROVED로 확인되지 않는 매장은 기존 STORE_007로 거절한다")
    void authorityRejectsStoreWithoutApprovedVerification() {
        given(storeService.getManagedStore(OPERATOR_ID, STORE_ID))
                .willReturn(managedStore(null, OperationStatus.OPEN));
        StoreServiceWaitingAuthorityAdapter adapter =
                new StoreServiceWaitingAuthorityAdapter(storeService);

        assertThatThrownBy(() -> adapter.requireRead(OPERATOR_ID, STORE_ID))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.VERIFICATION_STATE_CONFLICT));
    }

    @Test
    @DisplayName("입점 승인을 확인할 수 없으면 운영 상태보다 STORE_007을 우선한다")
    void verificationFailurePrecedesOperationStatusFailure() {
        given(storeService.getManagedStore(OPERATOR_ID, STORE_ID))
                .willReturn(managedStore(null, null));
        StoreServiceWaitingAuthorityAdapter adapter =
                new StoreServiceWaitingAuthorityAdapter(storeService);

        assertThatThrownBy(() -> adapter.requireRead(OPERATOR_ID, STORE_ID))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.VERIFICATION_STATE_CONFLICT));
    }

    @Test
    @DisplayName("StoreService의 계정 소유권 오류는 변환하지 않고 그대로 전파한다")
    void authorityPropagatesStoreServiceOwnershipFailure() {
        ServiceException denied = new ServiceException(StoreErrorCode.ACCESS_DENIED);
        given(storeService.getManagedStore(OPERATOR_ID, STORE_ID)).willThrow(denied);
        StoreServiceWaitingAuthorityAdapter adapter =
                new StoreServiceWaitingAuthorityAdapter(storeService);

        assertThatThrownBy(() -> adapter.requireRead(OPERATOR_ID, STORE_ID))
                .isSameAs(denied);
    }

    private void executeBusinessWork() {
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<BusinessResult<WaitingTeamSnapshot>> work = invocation.getArgument(1);
            BusinessResult<WaitingTeamSnapshot> result = work.get();
            return new IdempotentOutcome(
                    false,
                    result.httpStatus(),
                    result.responseCode(),
                    result.resourceType(),
                    result.resourceId(),
                    objectMapper.valueToTree(result.data())
            );
        });
    }

    private void allowCallWindow(WaitingTeam target) {
        given(sequenceRepository.findByStoreIdAndBusinessDateForUpdate(
                STORE_ID, target.getBusinessDate()))
                .willReturn(Optional.of(WaitingQueueSequence.create(
                        STORE_ID, target.getBusinessDate())));
        given(teamRepository.existsByStoreIdAndBusinessDateAndStatus(
                STORE_ID, target.getBusinessDate(), WaitingTeamStatus.CALLED))
                .willReturn(false);
    }

    private static IdempotencyCommand command(String type, String fingerprint) {
        return new IdempotencyCommand(
                "store-operator",
                OPERATOR_ID,
                type,
                "550e8400-e29b-41d4-a716-446655440000",
                fingerprint
        );
    }

    private static WaitingTeam team(long teamId, long storeId, WaitingTeamStatus status) {
        WaitingTeam team = WaitingTeam.create(
                storeId,
                51L,
                LocalDate.of(2026, 8, 12),
                2,
                WaitingSource.REMOTE,
                teamId,
                Instant.parse("2026-08-12T03:00:00Z")
        );
        ReflectionTestUtils.setField(team, "id", teamId);
        if (status == WaitingTeamStatus.CALLED) {
            team.call(0L, OCCURRED_AT);
        } else if (status == WaitingTeamStatus.ARRIVED) {
            team.call(0L, OCCURRED_AT);
            team.arrive(1L, OCCURRED_AT.plusSeconds(60));
        } else if (status == WaitingTeamStatus.RESERVATION_CONVERTING) {
            ReflectionTestUtils.setField(team, "status", status);
        }
        return team;
    }

    private static WaitingTeamSnapshot snapshot(WaitingTeamStatus status, long version) {
        return new WaitingTeamSnapshot(
                Long.toString(TEAM_ID),
                Long.toString(STORE_ID),
                status,
                TEAM_ID,
                2,
                Instant.parse("2026-08-12T03:00:00Z"),
                OCCURRED_AT,
                null,
                null,
                null,
                version
        );
    }

    private static ManagedStoreResponse managedStore(
            VerificationStatus verificationStatus,
            OperationStatus operationStatus
    ) {
        return new ManagedStoreResponse(
                Long.toString(STORE_ID),
                "미리윰",
                Region.SEOUL,
                "서울시 중구",
                "Asia/Seoul",
                "CAFE_BAKERY",
                verificationStatus,
                operationStatus,
                new StoreModesRequest(true, true, true),
                null
        );
    }
}
