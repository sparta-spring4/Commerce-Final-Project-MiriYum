package com.miriyum.domain.reservation.waiting.notification;

import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.reservation.waiting.entity.WaitingEntryImminentEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class WaitingNotificationEventFactory {

    public Optional<NotificationSourceEventV1> forStatus(
            WaitingStatusEvent statusEvent,
            WaitingTeam team
    ) {
        requireSameTeam(statusEvent.getWaitingTeamId(), team);
        NotificationPurpose purpose = purpose(statusEvent.getPublicStatus());
        if (purpose == null) {
            return Optional.empty();
        }
        Instant occurredAt = statusEvent.getPublicStatus() == WaitingTeamStatus.CALLED
                ? Objects.requireNonNull(team.getCalledAt(), "calledAt is required")
                : statusEvent.getOccurredAt();
        Instant expiresAt = statusEvent.getPublicStatus() == WaitingTeamStatus.CALLED
                ? Objects.requireNonNull(team.getArrivalDeadline(), "arrivalDeadline is required")
                : null;
        return Optional.of(event(
                "waiting-status-event:" + statusEvent.getId(),
                purpose,
                team,
                statusEvent.getEventSequence(),
                statusEvent.getPublicStatus().name(),
                occurredAt,
                expiresAt
        ));
    }

    public NotificationSourceEventV1 forEntryImminent(
            WaitingEntryImminentEvent entryEvent,
            WaitingTeam team
    ) {
        requireSameTeam(entryEvent.getWaitingTeamId(), team);
        return event(
                "waiting-entry-imminent:" + team.getId(),
                NotificationPurpose.WAITING_ENTRY_IMMINENT,
                team,
                entryEvent.getEventSequence(),
                WaitingTeamStatus.WAITING.name(),
                entryEvent.getOccurredAt(),
                null
        );
    }

    private static NotificationSourceEventV1 event(
            String sourceEventId,
            NotificationPurpose purpose,
            WaitingTeam team,
            long eventSequence,
            String sourceState,
            Instant occurredAt,
            Instant expiresAt
    ) {
        OffsetDateTime normalized = utc(occurredAt);
        return new NotificationSourceEventV1(
                sourceEventId,
                NotificationSourceDomain.WAITING,
                purpose,
                Long.toString(team.getConsumerAccountId()),
                1L,
                NotificationResourceType.WAITING_TEAM,
                Long.toString(team.getId()),
                eventSequence,
                sourceState,
                normalized,
                normalized,
                expiresAt == null ? null : utc(expiresAt),
                null,
                sourceEventId
        );
    }

    private static NotificationPurpose purpose(WaitingTeamStatus status) {
        return switch (status) {
            case CALLED -> NotificationPurpose.WAITING_CALLED;
            case CANCELLED -> NotificationPurpose.WAITING_CANCELLED;
            case NO_SHOW -> NotificationPurpose.WAITING_NO_SHOW;
            case CHECKED_IN -> NotificationPurpose.WAITING_CHECKED_IN;
            case CLOSED_BY_STORE -> NotificationPurpose.WAITING_CLOSED_BY_STORE;
            case WAITING, ARRIVED, RESERVATION_CONVERTING, RESERVATION_CONVERTED -> null;
        };
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(
                Objects.requireNonNull(value, "event time is required")
                        .truncatedTo(ChronoUnit.SECONDS),
                ZoneOffset.UTC
        );
    }

    private static void requireSameTeam(Long eventTeamId, WaitingTeam team) {
        Objects.requireNonNull(team, "team is required");
        if (eventTeamId == null || !eventTeamId.equals(team.getId())) {
            throw new IllegalArgumentException("waiting event and team must match");
        }
    }
}
