package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.notification.service.NotificationTaskRecorder;
import com.miriyum.domain.notification.service.WaitingNotificationReevaluationService;
import com.miriyum.domain.reservation.waiting.entity.WaitingEntryImminentEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEventPublicationState;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.notification.WaitingNotificationEventFactory;
import com.miriyum.domain.reservation.waiting.repository.WaitingEntryImminentEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Waiting 상태 사건을 Notification 작업과 팀별 1회 입장 임박 사건으로 투영한다. */
@Service
public class WaitingStatusEventDispatcher {

    private final WaitingStatusEventRepository events;
    private final WaitingTeamRepository teams;
    private final WaitingEntryImminentEventRepository entries;
    private final NotificationTaskRecorder recorder;
    private final WaitingNotificationReevaluationService reevaluation;
    private final WaitingNotificationEventFactory factory;
    private final Clock clock;

    public WaitingStatusEventDispatcher(
            WaitingStatusEventRepository events,
            WaitingTeamRepository teams,
            WaitingEntryImminentEventRepository entries,
            NotificationTaskRecorder recorder,
            WaitingNotificationReevaluationService reevaluation,
            WaitingNotificationEventFactory factory,
            Clock clock
    ) {
        this.events = events;
        this.teams = teams;
        this.entries = entries;
        this.recorder = recorder;
        this.reevaluation = reevaluation;
        this.factory = factory;
        this.clock = clock;
    }

    /** 한 상태 사건의 재판정·알림 기록·발행 완료를 한 트랜잭션으로 확정한다. */
    @Transactional
    public boolean dispatchNext() {
        WaitingStatusEvent event = events.findFirstByPublicationStateOrderByIdAsc(
                        WaitingStatusEventPublicationState.PENDING)
                .orElse(null);
        if (event == null) {
            return false;
        }
        WaitingTeam changedTeam = teams.findById(event.getWaitingTeamId())
                .orElseThrow(() -> new IllegalStateException(
                        "waiting status event references a missing team"));

        reevaluation.reevaluate(
                changedTeam.getId(), event.getId(), event.getEventSequence());
        factory.forStatus(event, changedTeam).ifPresent(recorder::record);
        recordNewEntryImminentEvents(changedTeam);
        event.markPublished();
        return true;
    }

    private void recordNewEntryImminentEvents(WaitingTeam changedTeam) {
        Instant occurredAt = clock.instant();
        for (WaitingTeam candidate : teams.findEntryImminentCandidates(
                changedTeam.getStoreId(), changedTeam.getBusinessDate())) {
            if (entries.findByWaitingTeamId(candidate.getId()).isPresent()) {
                continue;
            }
            WaitingEntryImminentEvent entry = entries.saveAndFlush(
                    WaitingEntryImminentEvent.record(
                            candidate.getId(), candidate.getVersion() + 1L, occurredAt));
            recorder.record(factory.forEntryImminent(entry, candidate));
        }
    }
}
