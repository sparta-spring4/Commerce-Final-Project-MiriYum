package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.global.sse.SseWakeUpRequester;
import com.miriyum.global.sse.SseWakeUpTarget;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WaitingStatusEventAppenderTest {

    @Test
    void appendsImmutablePublicEventAndRequestsAllAffectedScopesAfterCommit() {
        WaitingStatusEventRepository events = mock(WaitingStatusEventRepository.class);
        RecordingWakeUps wakeUps = new RecordingWakeUps();
        WaitingStatusEventAppender appender = new WaitingStatusEventAppender(events, wakeUps);
        WaitingTeam team = mock(WaitingTeam.class);
        given(team.getId()).willReturn(301L);
        given(team.getVersion()).willReturn(4L);
        given(team.getStatus()).willReturn(WaitingTeamStatus.CALLED);
        given(team.getConsumerAccountId()).willReturn(41L);
        given(team.getStoreId()).willReturn(103L);
        given(team.getBusinessDate()).willReturn(LocalDate.of(2026, 8, 19));
        Instant occurredAt = Instant.parse("2026-08-19T01:00:00Z");

        appender.append(team, occurredAt);

        ArgumentCaptor<WaitingStatusEvent> saved = ArgumentCaptor.forClass(WaitingStatusEvent.class);
        verify(events).save(saved.capture());
        assertThat(saved.getValue().getWaitingTeamId()).isEqualTo(301L);
        assertThat(saved.getValue().getEventSequence()).isEqualTo(5L);
        assertThat(saved.getValue().getPublicStatus()).isEqualTo(WaitingTeamStatus.CALLED);
        assertThat(saved.getValue().getOccurredAt()).isEqualTo(occurredAt);
        assertThat(wakeUps.targets).containsExactly(List.of(
                SseWakeUpTarget.waitingAccount(41L),
                SseWakeUpTarget.waitingStoreDate(103L, LocalDate.of(2026, 8, 19)),
                SseWakeUpTarget.waitingStore(103L)));
    }

    private static final class RecordingWakeUps implements SseWakeUpRequester {
        private final List<List<SseWakeUpTarget>> targets = new ArrayList<>();

        @Override
        public void afterCommit(Collection<SseWakeUpTarget> targets) {
            this.targets.add(List.copyOf(targets));
        }
    }
}
