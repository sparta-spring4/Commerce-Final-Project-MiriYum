package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WaitingStatusEventDispatcherTest {

    private final WaitingStatusEventRepository events = mock(WaitingStatusEventRepository.class);
    private final WaitingTeamRepository teams = mock(WaitingTeamRepository.class);
    private final WaitingEntryImminentEventRepository entries =
            mock(WaitingEntryImminentEventRepository.class);
    private final NotificationTaskRecorder recorder = mock(NotificationTaskRecorder.class);
    private final WaitingNotificationReevaluationService reevaluation =
            mock(WaitingNotificationReevaluationService.class);
    private final WaitingNotificationEventFactory factory =
            mock(WaitingNotificationEventFactory.class);
    private final WaitingStatusEventDispatcher dispatcher = new WaitingStatusEventDispatcher(
            events,
            teams,
            entries,
            recorder,
            reevaluation,
            factory,
            Clock.fixed(Instant.parse("2026-08-16T03:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void everyStatusEventReevaluatesBeforeItIsPublishedAndRecordsEligibleEntries() {
        WaitingStatusEvent status = mock(WaitingStatusEvent.class);
        WaitingTeam changed = mock(WaitingTeam.class);
        WaitingTeam candidate = mock(WaitingTeam.class);
        WaitingEntryImminentEvent savedEntry = mock(WaitingEntryImminentEvent.class);
        NotificationSourceEventV1 statusNotification = mock(NotificationSourceEventV1.class);
        NotificationSourceEventV1 entryNotification = mock(NotificationSourceEventV1.class);
        LocalDate businessDate = LocalDate.of(2026, 8, 16);

        given(events.findFirstByPublicationStateOrderByIdAsc(
                WaitingStatusEventPublicationState.PENDING)).willReturn(Optional.of(status));
        given(status.getId()).willReturn(91L);
        given(status.getWaitingTeamId()).willReturn(31L);
        given(status.getEventSequence()).willReturn(2L);
        given(teams.findById(31L)).willReturn(Optional.of(changed));
        given(changed.getId()).willReturn(31L);
        given(changed.getStoreId()).willReturn(21L);
        given(changed.getBusinessDate()).willReturn(businessDate);
        given(factory.forStatus(status, changed)).willReturn(Optional.of(statusNotification));
        given(teams.findEntryImminentCandidates(21L, businessDate))
                .willReturn(List.of(candidate));
        given(candidate.getId()).willReturn(32L);
        given(candidate.getVersion()).willReturn(0L);
        given(entries.findByWaitingTeamId(32L)).willReturn(Optional.empty());
        given(entries.saveAndFlush(any(WaitingEntryImminentEvent.class)))
                .willReturn(savedEntry);
        given(factory.forEntryImminent(savedEntry, candidate)).willReturn(entryNotification);

        assertThat(dispatcher.dispatchNext()).isTrue();

        then(reevaluation).should().reevaluate(31L, 91L, 2L);
        then(recorder).should().record(statusNotification);
        then(recorder).should().record(entryNotification);
        then(status).should().markPublished();
    }

    @Test
    void noPendingEventIsAnIdempotentNoOp() {
        given(events.findFirstByPublicationStateOrderByIdAsc(
                WaitingStatusEventPublicationState.PENDING)).willReturn(Optional.empty());

        assertThat(dispatcher.dispatchNext()).isFalse();

        then(reevaluation).shouldHaveNoInteractions();
        then(recorder).shouldHaveNoInteractions();
    }
}
