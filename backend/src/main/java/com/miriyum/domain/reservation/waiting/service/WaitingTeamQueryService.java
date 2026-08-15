package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamListItem;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamListQuery;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamPage;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamSnapshot;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 권한 판정 뒤 운영자에게 개인정보 안전한 원장 조회를 제공한다. */
@Service
@RequiredArgsConstructor
public class WaitingTeamQueryService {

    private final WaitingStoreAuthorityPort authorityPort;
    private final WaitingTeamRepository teamRepository;

    /** 매장 권한을 먼저 확인하고 선택 상태의 FIFO keyset 페이지를 반환한다. */
    @Transactional(readOnly = true)
    public WaitingTeamPage getTeams(
            long operatorAccountId,
            long storeId,
            WaitingTeamListQuery query
    ) {
        authorityPort.requireRead(operatorAccountId, storeId);
        List<WaitingTeam> fetched = teamRepository.findKeysetPage(
                storeId,
                query.status(),
                query.afterQueueSequence(),
                query.afterWaitingTeamId(),
                query.size() + 1);
        boolean hasNext = fetched.size() > query.size();
        List<WaitingTeam> visible = hasNext ? fetched.subList(0, query.size()) : fetched;
        String nextCursor = null;
        if (hasNext) {
            WaitingTeam last = visible.getLast();
            nextCursor = WaitingTeamListQuery.encode(last.getQueueSequence(), last.getId());
        }
        return new WaitingTeamPage(
                visible.stream().map(WaitingTeamListItem::from).toList(),
                nextCursor);
    }

    /** 매장 권한을 먼저 확인하고 해당 매장에 속한 팀만 상세 반환한다. */
    @Transactional(readOnly = true)
    public WaitingTeamSnapshot getTeam(
            long operatorAccountId,
            long storeId,
            long waitingTeamId
    ) {
        authorityPort.requireRead(operatorAccountId, storeId);
        WaitingTeam team = teamRepository.findByIdAndStoreId(waitingTeamId, storeId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.WAITING_TEAM_NOT_FOUND));
        return WaitingTeamSnapshot.from(team);
    }
}
