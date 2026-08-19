package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.global.sse.SseWakeUpRequester;
import com.miriyum.global.sse.SseWakeUpTarget;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Waiting 공개 상태 사건 저장과 영향 scope wake-up 요청을 한 경계로 유지한다. */
@Service
public class WaitingStatusEventAppender {

    private final WaitingStatusEventRepository eventRepository;
    private final SseWakeUpRequester wakeUps;

    public WaitingStatusEventAppender(
            WaitingStatusEventRepository eventRepository,
            SseWakeUpRequester wakeUps
    ) {
        this.eventRepository = Objects.requireNonNull(eventRepository);
        this.wakeUps = Objects.requireNonNull(wakeUps);
    }

    public void append(WaitingTeam team, Instant occurredAt) {
        Objects.requireNonNull(team);
        eventRepository.save(WaitingStatusEvent.pending(
                team.getId(),
                team.getVersion() + 1L,
                team.getStatus(),
                occurredAt
        ));
        wakeUps.afterCommit(List.of(
                SseWakeUpTarget.waitingAccount(team.getConsumerAccountId()),
                SseWakeUpTarget.waitingStoreDate(team.getStoreId(), team.getBusinessDate()),
                SseWakeUpTarget.waitingStore(team.getStoreId())
        ));
    }
}
