package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.waiting.config.WaitingHistoryProperties;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.HistoryQuery;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.HistoryItem;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.HistoryPage;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.Scope;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.HistoryStatus;
import com.miriyum.domain.reservation.waiting.entity.WaitingSource;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class WaitingConsumerHistoryQueryServiceTest {

    private static final Instant REGISTERED_AT = Instant.parse("2026-08-20T01:00:00Z");
    private static final Instant CALLED_AT = Instant.parse("2026-08-20T01:10:00Z");
    private static final Instant ARRIVED_AT = Instant.parse("2026-08-20T01:11:00Z");
    private static final Instant TERMINATED_AT = Instant.parse("2026-08-20T01:12:00Z");

    private final ConsumerAccountService accountService = mock(ConsumerAccountService.class);
    private final WaitingTeamRepository repository = mock(WaitingTeamRepository.class);
    private final WaitingConsumerHistoryCursorCodec cursorCodec =
            new WaitingConsumerHistoryCursorCodec(new WaitingHistoryProperties(
                    "test-waiting-history-cursor-secret-with-enough-entropy"));
    private final WaitingConsumerHistoryQueryService service =
            new WaitingConsumerHistoryQueryService(accountService, repository, cursorCodec);

    @Test
    void returnsNewestOwnedHistoryWithOpaqueNextCursor() {
        WaitingTeam newest = waiting(303L, REGISTERED_AT.plusSeconds(2));
        WaitingTeam boundary = waiting(302L, REGISTERED_AT.plusSeconds(1));
        WaitingTeam lookAhead = waiting(301L, REGISTERED_AT);
        given(repository.findConsumerHistoryPage(
                anyLong(), any(), any(), any(), anyInt()))
                .willReturn(List.of(newest, boundary, lookAhead));

        HistoryPage page = service.getHistory(200L, new HistoryQuery(Scope.ALL, null, 2));

        assertThat(page.items()).extracting(HistoryItem::waitingTeamId)
                .containsExactly("303", "302");
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(cursorCodec.decode(200L, Scope.ALL, page.nextCursor()))
                .isEqualTo(new WaitingConsumerHistoryCursorCodec.Boundary(
                        REGISTERED_AT.plusSeconds(1), 302L));
    }

    @Test
    void emptyHistoryIsSuccessfulEmptyPage() {
        given(repository.findConsumerHistoryPage(
                anyLong(), any(), any(), any(), anyInt()))
                .willReturn(List.of());

        HistoryPage page = service.getHistory(200L, HistoryQuery.from(null, null, null));

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void exposesOnlyNormalizedPublicFieldsForCheckedInHistory() {
        WaitingTeam checkedIn = waiting(301L, REGISTERED_AT);
        checkedIn.call(0L, CALLED_AT);
        checkedIn.arrive(1L, ARRIVED_AT);
        checkedIn.checkIn(2L, TERMINATED_AT);
        given(repository.findConsumerHistoryPage(
                anyLong(), any(), any(), any(), anyInt()))
                .willReturn(List.of(checkedIn));

        HistoryItem item = service.getHistory(
                200L, new HistoryQuery(Scope.TERMINAL, null, 20)).items().getFirst();

        assertThat(item).isEqualTo(new HistoryItem(
                "301", "100", HistoryStatus.CHECKED_IN, REGISTERED_AT,
                CALLED_AT, TERMINATED_AT));
    }

    @Test
    void normalizesCancellationAsTerminalTime() {
        WaitingTeam cancelled = waiting(301L, REGISTERED_AT);
        cancelled.cancel(0L, TERMINATED_AT);
        given(repository.findConsumerHistoryPage(
                anyLong(), any(), any(), any(), anyInt()))
                .willReturn(List.of(cancelled));

        HistoryItem item = service.getHistory(
                200L, new HistoryQuery(Scope.TERMINAL, null, 20)).items().getFirst();

        assertThat(item.terminatedAt()).isEqualTo(TERMINATED_AT);
    }

    @Test
    void calledHistoryExposesCallTimeWithoutTerminalTime() {
        WaitingTeam called = waiting(301L, REGISTERED_AT);
        called.call(0L, CALLED_AT);

        HistoryItem item = singleItem(called, Scope.CURRENT);

        assertThat(item.status()).isEqualTo(HistoryStatus.CALLED);
        assertThat(item.calledAt()).isEqualTo(CALLED_AT);
        assertThat(item.terminatedAt()).isNull();
    }

    @Test
    void noShowHistoryUsesNoShowTimeAsTerminalTime() {
        WaitingTeam noShow = waiting(301L, REGISTERED_AT);
        noShow.call(0L, CALLED_AT);
        Instant noShowAt = CALLED_AT.plusSeconds(600);
        noShow.markNoShow(1L, noShowAt);

        assertThat(singleItem(noShow, Scope.TERMINAL).terminatedAt()).isEqualTo(noShowAt);
    }

    @Test
    void storeClosedHistoryUsesClosureTimeAsTerminalTime() {
        WaitingTeam closed = waiting(301L, REGISTERED_AT);
        closed.closeByStore(0L, TERMINATED_AT);

        assertThat(singleItem(closed, Scope.TERMINAL).terminatedAt())
                .isEqualTo(TERMINATED_AT);
    }

    @Test
    void convertedHistoryUsesConversionCompletionAsTerminalTime() {
        WaitingTeam converted = waiting(301L, REGISTERED_AT);
        converted.beginReservationConversion(0L, "401", CALLED_AT);
        converted.completeReservationConversion(1L, "401", 501L, TERMINATED_AT);

        assertThat(singleItem(converted, Scope.TERMINAL).terminatedAt())
                .isEqualTo(TERMINATED_AT);
    }

    @Test
    void rejectsPageSizeOutsidePublicBoundary() {
        assertThatThrownBy(() -> HistoryQuery.from("ALL", null, 51))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingCursorSecretFailsClosedEvenForEmptyFirstPage() {
        given(repository.findConsumerHistoryPage(
                anyLong(), any(), any(), any(), anyInt()))
                .willReturn(List.of());
        WaitingConsumerHistoryQueryService unavailableService =
                new WaitingConsumerHistoryQueryService(
                        accountService,
                        repository,
                        new WaitingConsumerHistoryCursorCodec(new WaitingHistoryProperties("")));

        assertThatThrownBy(() -> unavailableService.getHistory(
                200L, new HistoryQuery(Scope.ALL, null, 20)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }

    private static WaitingTeam waiting(long id, Instant registeredAt) {
        WaitingTeam team = WaitingTeam.create(
                100L,
                200L,
                LocalDate.of(2026, 8, 20),
                2,
                WaitingSource.REMOTE,
                id,
                registeredAt);
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private HistoryItem singleItem(WaitingTeam team, Scope scope) {
        given(repository.findConsumerHistoryPage(
                anyLong(), any(), any(), any(), anyInt()))
                .willReturn(List.of(team));
        return service.getHistory(200L, new HistoryQuery(scope, null, 20)).items().getFirst();
    }
}
