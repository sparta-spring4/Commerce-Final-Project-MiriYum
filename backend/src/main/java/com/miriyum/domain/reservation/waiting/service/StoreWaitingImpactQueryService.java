package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.waiting.dto.StoreWaitingImpact;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreWaitingImpactQueryService {

    private static final Set<WaitingTeamStatus> ACTIVE_STATUSES = Set.of(
            WaitingTeamStatus.WAITING,
            WaitingTeamStatus.CALLED,
            WaitingTeamStatus.ARRIVED);

    private final WaitingTeamRepository waitingTeams;

    public StoreWaitingImpactQueryService(WaitingTeamRepository waitingTeams) {
        this.waitingTeams = waitingTeams;
    }

    @Transactional(readOnly = true)
    public StoreWaitingImpact inspect(long storeId) {
        if (storeId <= 0) {
            throw new IllegalArgumentException("storeId must be positive");
        }
        var ids = waitingTeams.findIdsByStoreIdAndStatusIn(storeId, ACTIVE_STATUSES);
        return new StoreWaitingImpact(storeId, ids.size(), Set.copyOf(ids));
    }
}
