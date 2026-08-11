package com.miriyum.domain.recommendation.ranking;

import com.miriyum.domain.menuhold.dto.MenuHoldItemResult;
import com.miriyum.domain.menuhold.service.MenuHoldSnapshotQueryService;
import com.miriyum.domain.reservation.dto.request.ReservationHistorySearchRequest;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryItemResponse;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryPageResponse;
import com.miriyum.domain.reservation.service.ReservationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class RecommendationHistoryLoader {

    private static final int HISTORY_LIMIT = 20;
    private static final int MENU_SNAPSHOT_LIMIT = 5;

    private final ReservationService reservationService;
    private final MenuHoldSnapshotQueryService menuHoldSnapshotQueryService;

    public RecommendationHistoryLoader(
            ReservationService reservationService,
            MenuHoldSnapshotQueryService menuHoldSnapshotQueryService
    ) {
        this.reservationService = reservationService;
        this.menuHoldSnapshotQueryService = menuHoldSnapshotQueryService;
    }

    public RecommendationHistorySnapshot load(Long consumerAccountId, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf is required");
        if (consumerAccountId == null || consumerAccountId <= 0) {
            return RecommendationHistorySnapshot.empty();
        }
        try {
            ReservationHistoryPageResponse response =
                    reservationService.getConsumerReservationHistory(
                            consumerAccountId,
                            ReservationHistorySearchRequest.from(
                                    "FULFILLED",
                                    0,
                                    HISTORY_LIMIT,
                                    "startAt,desc"));
            List<ParsedReservation> reservations = parse(response, asOf);
            List<RecommendationHistoryEvent> events = new ArrayList<>(reservations.size());
            for (int index = 0; index < reservations.size(); index++) {
                ParsedReservation reservation = reservations.get(index);
                Set<Long> menuIds = index < MENU_SNAPSHOT_LIMIT
                        ? loadMenuIds(reservation.reservationId())
                        : Set.of();
                events.add(new RecommendationHistoryEvent(
                        reservation.reservationId(),
                        reservation.storeId(),
                        reservation.occurredAt(),
                        menuIds));
            }
            return new RecommendationHistorySnapshot(events);
        } catch (RuntimeException exception) {
            return RecommendationHistorySnapshot.empty();
        }
    }

    private static List<ParsedReservation> parse(
            ReservationHistoryPageResponse response,
            Instant asOf
    ) {
        if (response == null
                || response.items() == null
                || response.page() == null
                || response.items().size() > HISTORY_LIMIT) {
            throw new IllegalArgumentException("invalid reservation history response");
        }
        List<ParsedReservation> parsed = new ArrayList<>(response.items().size());
        Set<Long> reservationIds = new HashSet<>();
        for (ReservationHistoryItemResponse item : response.items()) {
            if (item == null
                    || !"FULFILLED".equals(item.status())
                    || item.startAt() == null) {
                throw new IllegalArgumentException("invalid reservation history item");
            }
            long reservationId = parsePositive(item.reservationId());
            long storeId = parsePositive(item.storeId());
            Instant occurredAt = item.startAt().toInstant();
            if (!reservationIds.add(reservationId) || occurredAt.isAfter(asOf)) {
                throw new IllegalArgumentException("invalid reservation history identity");
            }
            parsed.add(new ParsedReservation(reservationId, storeId, occurredAt));
        }
        return List.copyOf(parsed);
    }

    private Set<Long> loadMenuIds(long reservationId) {
        List<MenuHoldItemResult> items =
                menuHoldSnapshotQueryService.findByReservationId(reservationId);
        if (items == null || items.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("invalid menu snapshot response");
        }
        Set<Long> menuIds = new LinkedHashSet<>();
        for (MenuHoldItemResult item : items) {
            if (item.menuId() <= 0) {
                throw new IllegalArgumentException("invalid menu snapshot identity");
            }
            menuIds.add(item.menuId());
        }
        return Set.copyOf(menuIds);
    }

    private static long parsePositive(String value) {
        if (value == null) {
            throw new IllegalArgumentException("identifier is required");
        }
        long parsed = Long.parseLong(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException("identifier must be positive");
        }
        return parsed;
    }

    private record ParsedReservation(
            long reservationId,
            long storeId,
            Instant occurredAt
    ) {
    }
}
