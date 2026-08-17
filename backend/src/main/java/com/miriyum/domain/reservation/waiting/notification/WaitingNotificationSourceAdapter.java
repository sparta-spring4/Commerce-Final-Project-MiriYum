package com.miriyum.domain.reservation.waiting.notification;

import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.notification.port.WaitingNotificationSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingStatusEvent;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingEntryImminentEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.store.service.StoreService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class WaitingNotificationSourceAdapter implements WaitingNotificationSource {

    private final WaitingTeamRepository teams;
    private final WaitingStatusEventRepository statuses;
    private final WaitingEntryImminentEventRepository entries;
    private final StoreService stores;

    public WaitingNotificationSourceAdapter(
            WaitingTeamRepository teams,
            WaitingStatusEventRepository statuses,
            WaitingEntryImminentEventRepository entries,
            StoreService stores
    ) {
        this.teams = teams;
        this.statuses = statuses;
        this.entries = entries;
        this.stores = stores;
    }

    @Override
    public NotificationSourceContextV1 readContext(
            NotificationPurpose purpose,
            String resourceId,
            long expectedVersion,
            String recipientAccountId
    ) {
        return readContext(purpose, resourceId, expectedVersion, recipientAccountId, false);
    }

    @Override
    public NotificationSourceContextV1 readContextForDelivery(
            NotificationPurpose purpose,
            String resourceId,
            long expectedVersion,
            String recipientAccountId
    ) {
        return readContext(purpose, resourceId, expectedVersion, recipientAccountId, true);
    }

    private NotificationSourceContextV1 readContext(
            NotificationPurpose purpose,
            String resourceId,
            long expectedVersion,
            String recipientAccountId,
            boolean delivery
    ) {
        Long teamId = parsePublicId(resourceId);
        Long recipientId = parsePublicId(recipientAccountId);
        if (teamId == null || recipientId == null || expectedVersion <= 0) {
            return empty(NotificationSourceReadResult.NOT_ELIGIBLE);
        }
        try {
            Optional<WaitingTeam> found = delivery
                    ? teams.findByIdForUpdate(teamId)
                    : teams.findById(teamId);
            if (found.isEmpty()) {
                return empty(NotificationSourceReadResult.NOT_ELIGIBLE);
            }
            WaitingTeam team = found.orElseThrow();
            if (!Objects.equals(team.getConsumerAccountId(), recipientId)) {
                return empty(NotificationSourceReadResult.NOT_ELIGIBLE);
            }
            if (purpose == NotificationPurpose.WAITING_ENTRY_IMMINENT) {
                return entries.findByWaitingTeamId(teamId)
                        .filter(value -> value.getEventSequence() == expectedVersion)
                        .map(ignored -> entryContext(team, expectedVersion))
                        .orElseGet(() -> empty(NotificationSourceReadResult.NOT_ELIGIBLE));
            }
            WaitingTeamStatus purposeStatus = statusForPurpose(purpose);
            if (purposeStatus == null) {
                return empty(NotificationSourceReadResult.NOT_ELIGIBLE);
            }
            return statuses.findByWaitingTeamIdAndEventSequence(teamId, expectedVersion)
                    .filter(event -> event.getPublicStatus() == purposeStatus)
                    .map(event -> statusContext(team, event, expectedVersion))
                    .orElseGet(() -> empty(NotificationSourceReadResult.NOT_ELIGIBLE));
        } catch (DataAccessException unavailable) {
            if (delivery) {
                throw unavailable;
            }
            return empty(NotificationSourceReadResult.TEMPORARILY_UNAVAILABLE);
        }
    }

    private NotificationSourceContextV1 entryContext(WaitingTeam team, long expectedVersion) {
        if (team.getStatus() == WaitingTeamStatus.RESERVATION_CONVERTING) {
            return state(
                    NotificationSourceReadResult.TEMPORARILY_UNAVAILABLE,
                    expectedVersion,
                    team.getStatus().name()
            );
        }
        if (team.getStatus() != WaitingTeamStatus.WAITING) {
            return state(
                    NotificationSourceReadResult.SUPERSEDED,
                    expectedVersion,
                    team.getStatus().name()
            );
        }
        return found(team, expectedVersion, WaitingTeamStatus.WAITING.name(), null, null);
    }

    private NotificationSourceContextV1 statusContext(
            WaitingTeam team,
            WaitingStatusEvent event,
            long expectedVersion
    ) {
        if (team.getStatus() != event.getPublicStatus()) {
            return state(
                    NotificationSourceReadResult.SUPERSEDED,
                    expectedVersion,
                    team.getStatus().name()
            );
        }
        Instant scheduledAt = team.getStatus() == WaitingTeamStatus.CALLED
                ? team.getCalledAt()
                : event.getOccurredAt();
        Instant expiresAt = team.getStatus() == WaitingTeamStatus.CALLED
                ? team.getArrivalDeadline()
                : null;
        return found(team, expectedVersion, team.getStatus().name(), scheduledAt, expiresAt);
    }

    private NotificationSourceContextV1 found(
            WaitingTeam team,
            long expectedVersion,
            String sourceState,
            Instant scheduledAt,
            Instant expiresAt
    ) {
        Optional<String> displayName = stores.findDisplayName(team.getStoreId());
        if (displayName.isEmpty()) {
            return empty(NotificationSourceReadResult.TEMPORARILY_UNAVAILABLE);
        }
        return new NotificationSourceContextV1(
                NotificationSourceReadResult.FOUND,
                expectedVersion,
                1L,
                sourceState,
                displayName.orElseThrow(),
                null,
                offset(scheduledAt),
                offset(expiresAt),
                null,
                null,
                null,
                null
        );
    }

    private static NotificationSourceContextV1 state(
            NotificationSourceReadResult result,
            long expectedVersion,
            String sourceState
    ) {
        return new NotificationSourceContextV1(
                result, expectedVersion, 1L, sourceState, null, null, null, null,
                null, null, null, null
        );
    }

    private static NotificationSourceContextV1 empty(NotificationSourceReadResult result) {
        return new NotificationSourceContextV1(
                result, 0L, 0L, null, null, null, null, null,
                null, null, null, null
        );
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static WaitingTeamStatus statusForPurpose(NotificationPurpose purpose) {
        if (purpose == null) {
            return null;
        }
        return switch (purpose) {
            case WAITING_CALLED -> WaitingTeamStatus.CALLED;
            case WAITING_CANCELLED -> WaitingTeamStatus.CANCELLED;
            case WAITING_NO_SHOW -> WaitingTeamStatus.NO_SHOW;
            case WAITING_CHECKED_IN -> WaitingTeamStatus.CHECKED_IN;
            case WAITING_CLOSED_BY_STORE -> WaitingTeamStatus.CLOSED_BY_STORE;
            default -> null;
        };
    }

    private static Long parsePublicId(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
