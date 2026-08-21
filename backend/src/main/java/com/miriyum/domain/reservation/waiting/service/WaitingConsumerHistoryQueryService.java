package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.HistoryItem;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.HistoryPage;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.HistoryQuery;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.HistoryStatus;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.Scope;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Waiting 영속성 내부에서 본인 웨이팅 이력을 공개 DTO로 투영한다. */
@Service
public class WaitingConsumerHistoryQueryService {

    private static final Set<WaitingTeamStatus> CURRENT_STATUSES = EnumSet.of(
            WaitingTeamStatus.WAITING,
            WaitingTeamStatus.CALLED,
            WaitingTeamStatus.ARRIVED,
            WaitingTeamStatus.RESERVATION_CONVERTING);
    private static final Set<WaitingTeamStatus> TERMINAL_STATUSES = EnumSet.of(
            WaitingTeamStatus.CHECKED_IN,
            WaitingTeamStatus.CANCELLED,
            WaitingTeamStatus.NO_SHOW,
            WaitingTeamStatus.CLOSED_BY_STORE,
            WaitingTeamStatus.RESERVATION_CONVERTED);

    private final ConsumerAccountService accountService;
    private final WaitingTeamRepository repository;
    private final WaitingConsumerHistoryCursorCodec cursorCodec;

    public WaitingConsumerHistoryQueryService(
            ConsumerAccountService accountService,
            WaitingTeamRepository repository,
            WaitingConsumerHistoryCursorCodec cursorCodec
    ) {
        this.accountService = Objects.requireNonNull(accountService);
        this.repository = Objects.requireNonNull(repository);
        this.cursorCodec = Objects.requireNonNull(cursorCodec);
    }

    /**
     * Access Token에서 얻은 소비자 계정 범위의 이력만 최신 등록순으로 조회한다.
     *
     * @param consumerAccountId 인증된 소비자 계정 ID
     * @param query 검증·정규화된 공개 조회 조건
     * @return 내부 식별자와 원장 정보를 제거한 keyset page
     */
    @Transactional(readOnly = true)
    public HistoryPage getHistory(long consumerAccountId, HistoryQuery query) {
        accountService.requireActiveAccount(consumerAccountId);
        Objects.requireNonNull(query);
        cursorCodec.requireAvailable();
        WaitingConsumerHistoryCursorCodec.Boundary boundary = query.cursor() == null
                ? null
                : cursorCodec.decode(consumerAccountId, query.scope(), query.cursor());
        List<WaitingTeam> rows = repository.findConsumerHistoryPage(
                consumerAccountId,
                statuses(query.scope()),
                boundary == null ? null : boundary.registeredAt(),
                boundary == null ? null : boundary.waitingTeamId(),
                query.size() + 1);
        boolean hasNext = rows.size() > query.size();
        List<WaitingTeam> pageRows = hasNext ? rows.subList(0, query.size()) : rows;
        List<HistoryItem> items = pageRows.stream().map(WaitingConsumerHistoryQueryService::item)
                .toList();
        String nextCursor = null;
        if (hasNext) {
            WaitingTeam last = pageRows.getLast();
            nextCursor = cursorCodec.encode(
                    consumerAccountId,
                    query.scope(),
                    new WaitingConsumerHistoryCursorCodec.Boundary(
                            last.getCreatedAt(), last.getId()));
        }
        return new HistoryPage(items, nextCursor);
    }

    private static Set<WaitingTeamStatus> statuses(Scope scope) {
        return switch (scope) {
            case ALL -> EnumSet.allOf(WaitingTeamStatus.class);
            case CURRENT -> EnumSet.copyOf(CURRENT_STATUSES);
            case TERMINAL -> EnumSet.copyOf(TERMINAL_STATUSES);
        };
    }

    private static HistoryItem item(WaitingTeam team) {
        return new HistoryItem(
                Long.toString(team.getId()),
                Long.toString(team.getStoreId()),
                HistoryStatus.valueOf(team.getStatus().name()),
                team.getCreatedAt(),
                team.getCalledAt(),
                terminatedAt(team));
    }

    private static Instant terminatedAt(WaitingTeam team) {
        return switch (team.getStatus()) {
            case CHECKED_IN -> team.getCheckedInAt();
            case CANCELLED -> team.getCancelledAt();
            case NO_SHOW -> team.getNoShowAt();
            case CLOSED_BY_STORE -> team.getClosedByStoreAt();
            case RESERVATION_CONVERTED -> team.getReservationConvertedAt();
            default -> null;
        };
    }
}
