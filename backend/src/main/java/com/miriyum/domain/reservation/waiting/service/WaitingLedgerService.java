package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingActiveTeamImpact;
import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamSnapshot;
import com.miriyum.domain.reservation.waiting.entity.WaitingActorType;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingTransitionAudit;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTransitionAuditRepository;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 운영자 권한, FIFO, version, 감사와 공개 사건을 한 명령 경계에서 조정한다. */
@Service
public class WaitingLedgerService {

    private static final Collection<WaitingTeamStatus> ACTIVE_STATUSES = java.util.List.of(
            WaitingTeamStatus.WAITING,
            WaitingTeamStatus.CALLED,
            WaitingTeamStatus.ARRIVED
    );
    private static final String RESOURCE_TYPE = "WAITING_TEAM";
    private static final String SUCCESS = "SUCCESS";

    private final WaitingStoreAuthorityPort authorityPort;
    private final WaitingTeamRepository teamRepository;
    private final WaitingActiveMembershipRepository membershipRepository;
    private final WaitingTransitionAuditRepository auditRepository;
    private final WaitingStatusEventRepository eventRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WaitingLedgerService(
            WaitingStoreAuthorityPort authorityPort,
            WaitingTeamRepository teamRepository,
            WaitingActiveMembershipRepository membershipRepository,
            WaitingTransitionAuditRepository auditRepository,
            WaitingStatusEventRepository eventRepository,
            IdempotencyExecutor idempotencyExecutor,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.authorityPort = Objects.requireNonNull(authorityPort);
        this.teamRepository = Objects.requireNonNull(teamRepository);
        this.membershipRepository = Objects.requireNonNull(membershipRepository);
        this.auditRepository = Objects.requireNonNull(auditRepository);
        this.eventRepository = Objects.requireNonNull(eventRepository);
        this.idempotencyExecutor = Objects.requireNonNull(idempotencyExecutor);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 권한 확인 뒤 대상 매장 범위의 개인정보 안전한 팀 snapshot을 반환한다. */
    @Transactional(readOnly = true)
    public WaitingTeamSnapshot getTeam(
            long operatorAccountId,
            long storeId,
            long waitingTeamId
    ) {
        authorityPort.requireRead(operatorAccountId, storeId);
        return WaitingTeamSnapshot.from(loadTeamInStore(waitingTeamId, storeId, false));
    }

    /** 권한 확인 뒤 WAITING/CALLED/ARRIVED 팀 수만 반환한다. */
    @Transactional(readOnly = true)
    public WaitingActiveTeamImpact inspectActiveTeams(long operatorAccountId, long storeId) {
        authorityPort.requireRead(operatorAccountId, storeId);
        return new WaitingActiveTeamImpact(
                storeId,
                teamRepository.countByStoreIdAndStatusIn(storeId, ACTIVE_STATUSES)
        );
    }

    /** FIFO 선두 WAITING 팀을 호출하고 감사·공개 사건을 같은 멱등 트랜잭션에 기록한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public WaitingCommandResult call(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            long expectedVersion,
            IdempotencyCommand command,
            Instant occurredAt
    ) {
        authorityPort.requireMutation(operatorAccountId, storeId);
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            WaitingTeam team = loadTeamInStore(waitingTeamId, storeId, true);
            requireVersion(team, expectedVersion);
            requireFifoHead(team);
            WaitingTeamStatus before = team.getStatus();
            team.call(expectedVersion, occurredAt);
            appendTransition(
                    team, operatorAccountId, before, expectedVersion,
                    "CALLED", command, occurredAt
            );
            return success(team);
        });
        return result(outcome);
    }

    /** CALLED 팀을 ARRIVED로 전이한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public WaitingCommandResult arrive(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            long expectedVersion,
            IdempotencyCommand command,
            Instant occurredAt
    ) {
        return transition(
                operatorAccountId, storeId, waitingTeamId, expectedVersion, command,
                occurredAt, "ARRIVED", WaitingTeam::arrive, false
        );
    }

    /** ARRIVED 팀을 CHECKED_IN으로 종결한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public WaitingCommandResult checkIn(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            long expectedVersion,
            IdempotencyCommand command,
            Instant occurredAt
    ) {
        return transition(
                operatorAccountId, storeId, waitingTeamId, expectedVersion, command,
                occurredAt, "CHECKED_IN", WaitingTeam::checkIn, true
        );
    }

    /** WAITING/CALLED/ARRIVED 팀을 CANCELLED로 종결한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public WaitingCommandResult cancel(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            long expectedVersion,
            IdempotencyCommand command,
            Instant occurredAt
    ) {
        return transition(
                operatorAccountId, storeId, waitingTeamId, expectedVersion, command,
                occurredAt, "CANCELLED_BY_STORE", WaitingTeam::cancel, true
        );
    }

    private WaitingCommandResult transition(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            long expectedVersion,
            IdempotencyCommand command,
            Instant occurredAt,
            String reason,
            TeamTransition mutation,
            boolean terminal
    ) {
        authorityPort.requireMutation(operatorAccountId, storeId);
        IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
            WaitingTeam team = loadTeamInStore(waitingTeamId, storeId, true);
            requireVersion(team, expectedVersion);
            WaitingTeamStatus before = team.getStatus();
            mutation.apply(team, expectedVersion, occurredAt);
            if (terminal) {
                removeMembership(team);
            }
            appendTransition(
                    team, operatorAccountId, before, expectedVersion,
                    reason, command, occurredAt
            );
            return success(team);
        });
        return result(outcome);
    }

    private WaitingTeam loadTeamInStore(long waitingTeamId, long storeId, boolean forUpdate) {
        WaitingTeam team = (forUpdate
                ? teamRepository.findByIdForUpdate(waitingTeamId)
                : teamRepository.findById(waitingTeamId))
                .orElseThrow(WaitingLedgerService::notFound);
        if (team.getStoreId() != storeId) {
            throw notFound();
        }
        return team;
    }

    private static void requireVersion(WaitingTeam team, long expectedVersion) {
        if (team.getVersion() != expectedVersion) {
            throw new ServiceException(ReservationErrorCode.WAITING_VERSION_CONFLICT);
        }
    }

    private void requireFifoHead(WaitingTeam team) {
        if (team.getStatus() != WaitingTeamStatus.WAITING) {
            throw new ServiceException(ReservationErrorCode.WAITING_INVALID_TRANSITION);
        }
        WaitingTeam head = teamRepository.findFifoHead(
                        team.getStoreId(), team.getBusinessDate(), WaitingTeamStatus.WAITING)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.WAITING_ACTIVE_MEMBERSHIP_CONFLICT));
        if (!head.getId().equals(team.getId())) {
            throw new ServiceException(ReservationErrorCode.WAITING_NOT_FIFO_HEAD);
        }
    }

    private void removeMembership(WaitingTeam team) {
        if (membershipRepository.deleteByWaitingTeamId(team.getId()) != 1L) {
            throw new ServiceException(
                    ReservationErrorCode.WAITING_ACTIVE_MEMBERSHIP_CONFLICT);
        }
    }

    private void appendTransition(
            WaitingTeam team,
            long operatorAccountId,
            WaitingTeamStatus before,
            long expectedVersion,
            String reason,
            IdempotencyCommand command,
            Instant occurredAt
    ) {
        Instant createdAt = clock.instant();
        auditRepository.save(WaitingTransitionAudit.record(
                team.getId(),
                WaitingActorType.STORE_OPERATOR,
                operatorAccountId,
                before,
                team.getStatus(),
                expectedVersion,
                reason,
                auditCommandId(command),
                occurredAt,
                createdAt
        ));
        eventRepository.save(WaitingStatusEvent.pending(
                team.getId(),
                team.getVersion() + 1L,
                team.getStatus(),
                occurredAt
        ));
    }

    private static String auditCommandId(IdempotencyCommand command) {
        return command.principalNamespace()
                + ':' + command.principalId()
                + ':' + command.commandType()
                + ':' + command.idempotencyKey();
    }

    private static BusinessResult<WaitingTeamSnapshot> success(WaitingTeam team) {
        return new BusinessResult<>(
                HttpStatus.OK.value(),
                SUCCESS,
                RESOURCE_TYPE,
                Long.toString(team.getId()),
                WaitingTeamSnapshot.from(team)
        );
    }

    private WaitingCommandResult result(IdempotentOutcome outcome) {
        return new WaitingCommandResult(
                outcome.httpStatus(),
                objectMapper.treeToValue(outcome.data(), WaitingTeamSnapshot.class)
        );
    }

    private static ServiceException notFound() {
        return new ServiceException(ReservationErrorCode.WAITING_TEAM_NOT_FOUND);
    }

    @FunctionalInterface
    private interface TeamTransition {
        void apply(WaitingTeam team, long expectedVersion, Instant occurredAt);
    }
}
