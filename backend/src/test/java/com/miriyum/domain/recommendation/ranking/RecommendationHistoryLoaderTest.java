package com.miriyum.domain.recommendation.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.miriyum.domain.menuhold.dto.MenuHoldItemResult;
import com.miriyum.domain.menuhold.service.MenuHoldSnapshotQueryService;
import com.miriyum.domain.reservation.dto.request.ReservationHistorySearchRequest;
import com.miriyum.domain.reservation.dto.response.CustomerReservationTimeStatus;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryItemResponse;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryPageResponse;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.global.response.PageMetadata;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationHistoryLoaderTest {

    private static final Instant AS_OF = Instant.parse("2026-08-06T06:00:00Z");

    @Mock ReservationService reservationService;
    @Mock MenuHoldSnapshotQueryService menuHoldSnapshotQueryService;

    @Test
    void anonymousSearchUsesAnEmptySnapshotWithoutHistoryCalls() {
        RecommendationHistorySnapshot result = loader().load(null, AS_OF);

        assertThat(result).isEqualTo(RecommendationHistorySnapshot.empty());
        verifyNoInteractions(reservationService, menuHoldSnapshotQueryService);
    }

    @Test
    void loadsTwentyFulfilledReservationsAndMenusForOnlyTheFirstFive() {
        List<ReservationHistoryItemResponse> items = new ArrayList<>();
        for (int index = 1; index <= 7; index++) {
            items.add(history(index, 100 + index, "FULFILLED", index));
        }
        given(reservationService.getConsumerReservationHistory(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.any(ReservationHistorySearchRequest.class)))
                .willReturn(page(items));
        for (long reservationId = 1L; reservationId <= 5L; reservationId++) {
            given(menuHoldSnapshotQueryService.findByReservationId(reservationId))
                    .willReturn(List.of(new MenuHoldItemResult(
                            1_000L + reservationId, "메뉴", 5_000L, 1)));
        }

        RecommendationHistorySnapshot result = loader().load(41L, AS_OF);

        assertThat(result.events()).hasSize(7);
        assertThat(result.events().getFirst())
                .isEqualTo(new RecommendationHistoryEvent(
                        1L,
                        101L,
                        AS_OF.minusSeconds(86_400L),
                        java.util.Set.of(1_001L)));
        assertThat(result.events().get(4).menuIds()).containsExactly(1_005L);
        assertThat(result.events().get(5).menuIds()).isEmpty();
        assertThat(result.events().get(6).menuIds()).isEmpty();
        verify(menuHoldSnapshotQueryService, never()).findByReservationId(6L);
        verify(menuHoldSnapshotQueryService, never()).findByReservationId(7L);

        ArgumentCaptor<ReservationHistorySearchRequest> request =
                ArgumentCaptor.forClass(ReservationHistorySearchRequest.class);
        verify(reservationService).getConsumerReservationHistory(
                org.mockito.ArgumentMatchers.eq(41L), request.capture());
        assertThat(request.getValue().status())
                .isEqualTo(ReservationHistorySearchRequest.Status.FULFILLED);
        assertThat(request.getValue().page()).isZero();
        assertThat(request.getValue().size()).isEqualTo(20);
        assertThat(request.getValue().order())
                .isEqualTo(ReservationHistorySearchRequest.Order.CREATED_AT_DESC);
    }

    @Test
    void anyPublicContractFailureDiscardsTheWholePersonalSnapshot() {
        List<ReservationHistoryItemResponse> items = List.of(
                history(1, 101, "FULFILLED", 1),
                history(2, 102, "FULFILLED", 2));
        given(reservationService.getConsumerReservationHistory(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.any(ReservationHistorySearchRequest.class)))
                .willReturn(page(items));
        given(menuHoldSnapshotQueryService.findByReservationId(1L))
                .willReturn(List.of(new MenuHoldItemResult(1_001L, "메뉴", 5_000L, 1)));
        given(menuHoldSnapshotQueryService.findByReservationId(2L))
                .willThrow(new IllegalStateException("snapshot unavailable"));

        RecommendationHistorySnapshot result = loader().load(41L, AS_OF);

        assertThat(result).isEqualTo(RecommendationHistorySnapshot.empty());
    }

    @Test
    void malformedReservationIdsDiscardTheWholePersonalSnapshot() {
        given(reservationService.getConsumerReservationHistory(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.any(ReservationHistorySearchRequest.class)))
                .willReturn(page(List.of(new ReservationHistoryItemResponse(
                        "not-a-number",
                        "101",
                        "매장",
                        LocalDate.of(2026, 8, 5),
                        CustomerReservationTimeStatus.RESOLVED,
                        OffsetDateTime.ofInstant(AS_OF.minusSeconds(86_400L), ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(AS_OF.minusSeconds(82_800L), ZoneOffset.UTC),
                        "Asia/Seoul",
                        2,
                        "FULFILLED",
                        OffsetDateTime.ofInstant(AS_OF.minusSeconds(172_800L), ZoneOffset.UTC)))));

        assertThat(loader().load(41L, AS_OF))
                .isEqualTo(RecommendationHistorySnapshot.empty());
        verifyNoInteractions(menuHoldSnapshotQueryService);
    }

    @Test
    void duplicateReservationsOrInvalidMenuIdsDiscardTheWholeSnapshot() {
        ReservationHistoryItemResponse duplicate = history(1, 101, "FULFILLED", 1);
        given(reservationService.getConsumerReservationHistory(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.any(ReservationHistorySearchRequest.class)))
                .willReturn(page(List.of(duplicate, duplicate)));

        assertThat(loader().load(41L, AS_OF))
                .isEqualTo(RecommendationHistorySnapshot.empty());

        org.mockito.Mockito.reset(reservationService, menuHoldSnapshotQueryService);
        given(reservationService.getConsumerReservationHistory(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.any(ReservationHistorySearchRequest.class)))
                .willReturn(page(List.of(history(2, 102, "FULFILLED", 1))));
        given(menuHoldSnapshotQueryService.findByReservationId(2L))
                .willReturn(List.of(new MenuHoldItemResult(0L, "메뉴", 5_000L, 1)));

        assertThat(loader().load(41L, AS_OF))
                .isEqualTo(RecommendationHistorySnapshot.empty());
    }

    @Test
    void wrongStatusOrFutureFulfilledEventDiscardsTheWholeSnapshot() {
        given(reservationService.getConsumerReservationHistory(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.any(ReservationHistorySearchRequest.class)))
                .willReturn(page(List.of(history(1, 101, "CANCELLED", 1))));

        assertThat(loader().load(41L, AS_OF))
                .isEqualTo(RecommendationHistorySnapshot.empty());

        org.mockito.Mockito.reset(reservationService, menuHoldSnapshotQueryService);
        ReservationHistoryItemResponse future = history(2, 102, "FULFILLED", -1);
        given(reservationService.getConsumerReservationHistory(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.any(ReservationHistorySearchRequest.class)))
                .willReturn(page(List.of(future)));

        assertThat(loader().load(41L, AS_OF))
                .isEqualTo(RecommendationHistorySnapshot.empty());
    }

    private RecommendationHistoryLoader loader() {
        return new RecommendationHistoryLoader(
                reservationService, menuHoldSnapshotQueryService);
    }

    private static ReservationHistoryPageResponse page(
            List<ReservationHistoryItemResponse> items
    ) {
        return new ReservationHistoryPageResponse(
                items,
                new PageMetadata(0, 20, items.size(), 1, false));
    }

    private static ReservationHistoryItemResponse history(
            long reservationId,
            long storeId,
            String status,
            int daysAgo
    ) {
        OffsetDateTime startAt = OffsetDateTime.ofInstant(
                AS_OF.minusSeconds(daysAgo * 86_400L), ZoneOffset.UTC);
        return new ReservationHistoryItemResponse(
                Long.toString(reservationId),
                Long.toString(storeId),
                "매장 " + storeId,
                startAt.toLocalDate(),
                CustomerReservationTimeStatus.RESOLVED,
                startAt,
                startAt.plusHours(1),
                "Asia/Seoul",
                2,
                status,
                startAt.minusDays(1));
    }
}
