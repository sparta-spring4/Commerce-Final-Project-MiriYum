package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryItem;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryPage;
import com.miriyum.domain.reservation.waiting.dto.WaitingReceptionAvailability;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.store.service.StoreAdministrationService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.exception.CommonErrorCode;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 소비자 계정 범위에서 현재 활성 웨이팅만 조회하고 공개 snapshot으로 투영한다. */
@Service
public class WaitingConsumerQueryService {

    private static final Set<WaitingTeamStatus> TERMINAL_STATUSES = Set.of(
            WaitingTeamStatus.CHECKED_IN,
            WaitingTeamStatus.CANCELLED,
            WaitingTeamStatus.NO_SHOW,
            WaitingTeamStatus.CLOSED_BY_STORE,
            WaitingTeamStatus.RESERVATION_CONVERTED);

    private final ConsumerAccountService accountService;
    private final WaitingActiveMembershipRepository membershipRepository;
    private final WaitingTeamRepository teamRepository;
    private final WaitingReceptionGate receptionGate;
    private final StoreAdministrationService storeAdministrationService;
    private final Clock clock;

    public WaitingConsumerQueryService(
            ConsumerAccountService accountService,
            WaitingActiveMembershipRepository membershipRepository,
            WaitingTeamRepository teamRepository,
            WaitingReceptionGate receptionGate,
            StoreAdministrationService storeAdministrationService,
            Clock clock
    ) {
        this.accountService = Objects.requireNonNull(accountService);
        this.membershipRepository = Objects.requireNonNull(membershipRepository);
        this.teamRepository = Objects.requireNonNull(teamRepository);
        this.receptionGate = Objects.requireNonNull(receptionGate);
        this.storeAdministrationService = Objects.requireNonNull(storeAdministrationService);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public WaitingReceptionAvailability getAvailability(
            long consumerAccountId,
            long storeId
    ) {
        accountService.requireActiveAccount(consumerAccountId);
        storeAdministrationService.requireStoreExists(storeId);
        return receptionGate.inspect(storeId, clock.instant());
    }

    @Transactional(readOnly = true)
    public WaitingConsumerSnapshot getCurrent(long consumerAccountId) {
        accountService.requireActiveAccount(consumerAccountId);
        WaitingActiveMembership membership = membershipRepository
                .findByConsumerAccountId(consumerAccountId)
                .orElseThrow(WaitingConsumerQueryService::notFound);
        WaitingTeam team = teamRepository.findById(membership.getWaitingTeamId())
                .orElseThrow(WaitingConsumerQueryService::notFound);
        return snapshot(team, consumerAccountId);
    }

    @Transactional(readOnly = true)
    public WaitingConsumerHistoryPage getHistory(
            long consumerAccountId,
            int page,
            int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        accountService.requireActiveAccount(consumerAccountId);
        var pageable = PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return WaitingConsumerHistoryPage.of(teamRepository
                .findAllByConsumerAccountIdAndStatusIn(
                        consumerAccountId, TERMINAL_STATUSES, pageable)
                .map(WaitingConsumerHistoryItem::from));
    }

    @Transactional(readOnly = true)
    public WaitingConsumerSnapshot getOwned(long consumerAccountId, long waitingTeamId) {
        accountService.requireActiveAccount(consumerAccountId);
        WaitingTeam team = teamRepository.findById(waitingTeamId)
                .filter(candidate -> candidate.getConsumerAccountId() == consumerAccountId)
                .orElseThrow(WaitingConsumerQueryService::notFound);
        return snapshot(team, consumerAccountId);
    }

    private WaitingConsumerSnapshot snapshot(WaitingTeam team, long viewerAccountId) {
        long teamsAhead = teamRepository.countActiveAhead(
                team.getStoreId(), team.getBusinessDate(), team.getQueueSequence());
        return WaitingConsumerSnapshot.from(
                team,
                teamsAhead,
                membershipRepository.findAllByWaitingTeamIdOrderById(team.getId()),
                viewerAccountId);
    }

    private static ServiceException notFound() {
        return new ServiceException(ReservationErrorCode.WAITING_TEAM_NOT_FOUND);
    }
}
