package com.miriyum.domain.reservation.waiting.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.reservation.waiting.entity.WaitingEntryImminentEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingEntryImminentEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.store.service.StoreService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WaitingNotificationSourceAdapterTest {

    private final WaitingTeamRepository teams = mock(WaitingTeamRepository.class);
    private final WaitingStatusEventRepository statuses = mock(WaitingStatusEventRepository.class);
    private final WaitingEntryImminentEventRepository entries =
            mock(WaitingEntryImminentEventRepository.class);
    private final StoreService stores = mock(StoreService.class);
    private final WaitingNotificationSourceAdapter source =
            new WaitingNotificationSourceAdapter(teams, statuses, entries, stores);
    private final WaitingTeam team = mock(WaitingTeam.class);

    @BeforeEach
    void setUp() {
        given(teams.findById(31L)).willReturn(Optional.of(team));
        given(teams.findByIdForUpdate(31L)).willReturn(Optional.of(team));
        given(team.getId()).willReturn(31L);
        given(team.getConsumerAccountId()).willReturn(11L);
        given(team.getStoreId()).willReturn(21L);
        given(stores.findDisplayName(21L)).willReturn(Optional.of("미리윰 강남"));
    }

    @Test
    void entryImminentSurvivesConversionHoldAndFailedConversionReturn() {
        WaitingEntryImminentEvent entry = entry(1L);
        given(entries.findByWaitingTeamId(31L)).willReturn(Optional.of(entry));

        given(team.getStatus()).willReturn(WaitingTeamStatus.RESERVATION_CONVERTING);
        var held = source.readContext("31", 1L, "11");
        assertThat(held.result()).isEqualTo(NotificationSourceReadResult.TEMPORARILY_UNAVAILABLE);
        assertThat(held.resourceVersion()).isEqualTo(1L);
        assertThat(held.sourceState()).isEqualTo("RESERVATION_CONVERTING");

        given(team.getStatus()).willReturn(WaitingTeamStatus.WAITING);
        var returned = source.readContext("31", 1L, "11");
        assertThat(returned.result()).isEqualTo(NotificationSourceReadResult.FOUND);
        assertThat(returned.resourceVersion()).isEqualTo(1L);
        assertThat(returned.storeDisplayName()).isEqualTo("미리윰 강남");
    }

    @Test
    void newerCalledStateSupersedesTheOldEntryEvent() {
        WaitingEntryImminentEvent entry = entry(1L);
        given(entries.findByWaitingTeamId(31L)).willReturn(Optional.of(entry));
        given(team.getStatus()).willReturn(WaitingTeamStatus.CALLED);

        assertThat(source.readContext("31", 1L, "11").result())
                .isEqualTo(NotificationSourceReadResult.SUPERSEDED);
    }

    @Test
    void recipientComparisonUsesTheLongValueOutsideTheJvmCacheRange() {
        WaitingEntryImminentEvent entry = entry(1L);
        given(team.getConsumerAccountId()).willReturn(1_000L);
        given(team.getStatus()).willReturn(WaitingTeamStatus.WAITING);
        given(entries.findByWaitingTeamId(31L)).willReturn(Optional.of(entry));

        assertThat(source.readContext("31", 1L, "1000").result())
                .isEqualTo(NotificationSourceReadResult.FOUND);
    }

    @Test
    void calledContextReturnsCentralTimesAndRecipientMismatchFailsClosed() {
        Instant calledAt = Instant.parse("2026-08-16T03:00:00Z");
        WaitingStatusEvent status = mock(WaitingStatusEvent.class);
        given(status.getPublicStatus()).willReturn(WaitingTeamStatus.CALLED);
        given(statuses.findByWaitingTeamIdAndEventSequence(31L, 2L))
                .willReturn(Optional.of(status));
        given(entries.findByWaitingTeamId(31L)).willReturn(Optional.empty());
        given(team.getStatus()).willReturn(WaitingTeamStatus.CALLED);
        given(team.getCalledAt()).willReturn(calledAt);
        given(team.getArrivalDeadline()).willReturn(calledAt.plusSeconds(600));

        var found = source.readContext("31", 2L, "11");
        assertThat(found.result()).isEqualTo(NotificationSourceReadResult.FOUND);
        assertThat(found.scheduledAt().toInstant()).isEqualTo(calledAt);
        assertThat(found.expiresAt().toInstant()).isEqualTo(calledAt.plusSeconds(600));

        assertThat(source.readContext("31", 2L, "12").result())
                .isEqualTo(NotificationSourceReadResult.NOT_ELIGIBLE);
    }

    private static WaitingEntryImminentEvent entry(long sequence) {
        WaitingEntryImminentEvent entry = mock(WaitingEntryImminentEvent.class);
        given(entry.getEventSequence()).willReturn(sequence);
        return entry;
    }
}
