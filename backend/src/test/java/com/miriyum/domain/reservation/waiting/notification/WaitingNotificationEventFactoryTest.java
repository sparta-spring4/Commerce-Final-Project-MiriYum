package com.miriyum.domain.reservation.waiting.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.reservation.waiting.entity.WaitingEntryImminentEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WaitingNotificationEventFactoryTest {

    private final WaitingNotificationEventFactory factory =
            new WaitingNotificationEventFactory();

    @Test
    void calledUsesCentralCalledAtAndExactlyTenMinuteArrivalDeadline() {
        Instant calledAt = Instant.parse("2026-08-16T03:00:00Z");
        WaitingTeam team = team(31L, 11L, WaitingTeamStatus.CALLED);
        given(team.getCalledAt()).willReturn(calledAt);
        given(team.getArrivalDeadline()).willReturn(calledAt.plusSeconds(600));
        WaitingStatusEvent event = statusEvent(91L, 31L, 2L,
                WaitingTeamStatus.CALLED, calledAt);

        var notification = factory.forStatus(event, team).orElseThrow();

        assertThat(notification.purpose()).isEqualTo(NotificationPurpose.WAITING_CALLED);
        assertThat(notification.occurredAt().toInstant()).isEqualTo(calledAt);
        assertThat(notification.scheduledAt().toInstant()).isEqualTo(calledAt);
        assertThat(notification.expiresAt().toInstant()).isEqualTo(calledAt.plusSeconds(600));
        assertThat(notification.resourceVersion()).isEqualTo(2L);
    }

    @Test
    void purposeLessStatusIsNotMisrepresentedAsAnotherNotification() {
        WaitingTeam team = team(31L, 11L, WaitingTeamStatus.ARRIVED);
        WaitingStatusEvent event = statusEvent(92L, 31L, 3L,
                WaitingTeamStatus.ARRIVED, Instant.parse("2026-08-16T03:01:00Z"));

        assertThat(factory.forStatus(event, team)).isEmpty();
    }

    @Test
    void entryImminentUsesItsFixedSequenceWithoutChangingTeamState() {
        WaitingTeam team = team(31L, 11L, WaitingTeamStatus.WAITING);
        WaitingEntryImminentEvent entry = mock(WaitingEntryImminentEvent.class);
        given(entry.getWaitingTeamId()).willReturn(31L);
        given(entry.getEventSequence()).willReturn(1L);
        given(entry.getOccurredAt()).willReturn(Instant.parse("2026-08-16T02:59:00Z"));

        var notification = factory.forEntryImminent(entry, team);

        assertThat(notification.purpose())
                .isEqualTo(NotificationPurpose.WAITING_ENTRY_IMMINENT);
        assertThat(notification.resourceVersion()).isEqualTo(1L);
        assertThat(notification.sourceState()).isEqualTo("WAITING");
    }

    private static WaitingTeam team(long id, long consumerId, WaitingTeamStatus status) {
        WaitingTeam team = mock(WaitingTeam.class);
        given(team.getId()).willReturn(id);
        given(team.getConsumerAccountId()).willReturn(consumerId);
        given(team.getStatus()).willReturn(status);
        return team;
    }

    private static WaitingStatusEvent statusEvent(
            long id,
            long teamId,
            long sequence,
            WaitingTeamStatus status,
            Instant occurredAt
    ) {
        WaitingStatusEvent event = mock(WaitingStatusEvent.class);
        given(event.getId()).willReturn(id);
        given(event.getWaitingTeamId()).willReturn(teamId);
        given(event.getEventSequence()).willReturn(sequence);
        given(event.getPublicStatus()).willReturn(status);
        given(event.getOccurredAt()).willReturn(occurredAt);
        return event;
    }
}
