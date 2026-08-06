package com.miriyum.domain.store.search.service;

import static com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus.AVAILABLE;
import static com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.recommendation.ranking.RankedRecommendation;
import com.miriyum.domain.store.recommendation.ranking.RecommendationAvailability;
import com.miriyum.domain.store.recommendation.ranking.RecommendationCandidate;
import com.miriyum.domain.store.recommendation.ranking.StoreRecommendationService;
import com.miriyum.domain.store.search.dto.ReservationAvailability;
import com.miriyum.domain.store.search.config.StoreSearchCandidateLimit;
import com.miriyum.domain.store.search.interpreter.InterpretationResult;
import com.miriyum.domain.store.search.interpreter.InterpretedSearchCondition;
import com.miriyum.domain.store.search.query.IntegratedSearchCursorCodec;
import com.miriyum.domain.store.search.query.IntegratedStoreSearchQuery;
import com.miriyum.domain.store.search.repository.IntegratedStoreSearchCandidate;
import com.miriyum.domain.store.search.repository.IntegratedStoreSearchRepository;
import com.miriyum.domain.store.search.repository.IntegratedStoreSearchSlice;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntegratedStoreSearchServiceTest {

    private static final IntegratedSearchCursorCodec CURSOR_CODEC =
            new IntegratedSearchCursorCodec(
                    "test-only-secret-key-must-be-at-least-32-bytes");

    @Mock IntegratedSearchInterpreter interpreter;
    @Mock IntegratedStoreSearchRepository repository;
    @Mock ReservationService reservationService;
    @Mock StoreSearchCandidateLimit candidateLimit;
    @Mock StoreRecommendationService recommendationService;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-08-06T06:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void useProductionCandidateLimit() {
        given(candidateLimit.value()).willReturn(5_000);
    }

    @Test
    void returnsNotRequestedAndVerifiedCoordinatesWithoutReservationCondition() {
        InterpretedSearchCondition condition = condition(null, null, null, "라멘");
        given(interpreter.interpret("라멘")).willReturn(result(condition));
        IntegratedStoreSearchCandidate candidate = candidate(1L, "라멘집");
        given(repository.search(any())).willReturn(
                new IntegratedStoreSearchSlice(List.of(candidate), null));
        given(repository.refreshCurrentlyPublic(List.of(candidate)))
                .willReturn(List.of(candidate));

        var data = service().search("라멘", false, false, null, null, 20);

        assertThat(data.items()).singleElement().satisfies(item -> {
            assertThat(item.reservationAvailability())
                    .isEqualTo(ReservationAvailability.NOT_REQUESTED);
            assertThat(item.coordinates().latitude())
                    .isEqualByComparingTo("37.500000000000000");
        });
        then(reservationService).shouldHaveNoInteractions();
    }

    @Test
    void failsClosedWhenReservationBatchDoesNotMatchCandidateIds() {
        InterpretedSearchCondition condition = condition(
                LocalDate.of(2026, 8, 8), LocalTime.of(18, 0), 2, "");
        given(interpreter.interpret("내일 18시 2명")).willReturn(result(condition));
        IntegratedStoreSearchCandidate candidate = candidate(1L, "예약집");
        given(repository.search(any())).willReturn(
                new IntegratedStoreSearchSlice(List.of(candidate), null));
        given(repository.refreshCurrentlyPublic(List.of(candidate)))
                .willReturn(List.of(candidate));
        given(reservationService.getAvailabilities(any(), any()))
                .willReturn(List.of(new ReservationAvailabilityResult(999L, AVAILABLE)));

        var data = service().search(
                "내일 18시 2명", false, true, null, null, 20);

        assertThat(data.items()).isEmpty();
    }

    @Test
    void excludesMismatchedReservationBatchEvenWhenUnavailableStoresAreRequested() {
        InterpretedSearchCondition condition = condition(
                LocalDate.of(2026, 8, 8), LocalTime.of(18, 0), 2, "");
        given(interpreter.interpret("내일 18시 2명")).willReturn(result(condition));
        IntegratedStoreSearchCandidate candidate = candidate(1L, "예약집");
        given(repository.search(any())).willReturn(
                new IntegratedStoreSearchSlice(List.of(candidate), null));
        given(repository.refreshCurrentlyPublic(List.of(candidate)))
                .willReturn(List.of(candidate));
        given(reservationService.getAvailabilities(any(), any()))
                .willReturn(List.of(new ReservationAvailabilityResult(999L, AVAILABLE)));

        var data = service().search(
                "내일 18시 2명", false, false, null, null, 20);

        assertThat(data.items()).isEmpty();
    }

    @Test
    void availableOnlyContinuesStaticCursorUntilRequestedSizeIsFilled() {
        InterpretedSearchCondition condition = condition(
                LocalDate.of(2026, 8, 8), LocalTime.of(18, 0), 2, "");
        given(interpreter.interpret("내일 18시 2명")).willReturn(result(condition));
        IntegratedStoreSearchCandidate first = candidate(1L, "가게1");
        IntegratedStoreSearchCandidate second = candidate(2L, "가게2");
        IntegratedStoreSearchCandidate third = candidate(3L, "가게3");
        IntegratedStoreSearchQuery firstQuery = IntegratedStoreSearchQuery.from(
                condition, null, null, 2, CURSOR_CODEC);
        String next = CURSOR_CODEC.encode(
                firstQuery, second.relevanceTier(), second.name(), second.storeId());
        given(repository.search(any()))
                .willReturn(new IntegratedStoreSearchSlice(List.of(first, second), next))
                .willReturn(new IntegratedStoreSearchSlice(List.of(third), null));
        given(repository.refreshCurrentlyPublic(any()))
                .willAnswer(invocation -> List.copyOf(invocation.getArgument(0)));
        given(reservationService.getAvailabilities(any(), any()))
                .willReturn(List.of(
                        new ReservationAvailabilityResult(1L, UNAVAILABLE),
                        new ReservationAvailabilityResult(2L, AVAILABLE)))
                .willReturn(List.of(new ReservationAvailabilityResult(3L, AVAILABLE)));

        var data = service().search(
                "내일 18시 2명", false, true, null, null, 2);

        assertThat(data.items()).extracting(item -> item.storeId())
                .containsExactly("2", "3");
        assertThat(data.nextCursor()).isNull();
    }

    @Test
    void availableOnlyStopsAtConfiguredCandidateLimit() {
        given(candidateLimit.value()).willReturn(2);
        InterpretedSearchCondition condition = condition(
                LocalDate.of(2026, 8, 8), LocalTime.of(18, 0), 2, "");
        given(interpreter.interpret("내일 18시 2명")).willReturn(result(condition));
        IntegratedStoreSearchCandidate first = candidate(1L, "가게1");
        IntegratedStoreSearchCandidate second = candidate(2L, "가게2");
        IntegratedStoreSearchQuery firstQuery = IntegratedStoreSearchQuery.from(
                condition, null, null, 3, CURSOR_CODEC);
        String next = CURSOR_CODEC.encode(
                firstQuery, second.relevanceTier(), second.name(), second.storeId());
        given(repository.search(any())).willReturn(
                new IntegratedStoreSearchSlice(List.of(first, second), next));
        given(repository.refreshCurrentlyPublic(any()))
                .willAnswer(invocation -> List.copyOf(invocation.getArgument(0)));
        given(reservationService.getAvailabilities(any(), any())).willReturn(List.of(
                new ReservationAvailabilityResult(1L, UNAVAILABLE),
                new ReservationAvailabilityResult(2L, UNAVAILABLE)));

        var data = service().search(
                "내일 18시 2명", false, true, null, null, 3);

        assertThat(data.items()).isEmpty();
        assertThat(data.nextCursor()).isNull();
        then(repository).should(times(1)).search(any());
    }

    @Test
    void recommendationSortRanksTheWholeBoundedCandidateSetAndPagesBySignedRankKey() {
        given(candidateLimit.value()).willReturn(3);
        InterpretedSearchCondition condition = condition(null, null, null, "라멘");
        given(interpreter.interpret("라멘")).willReturn(result(condition));
        IntegratedStoreSearchCandidate first = candidate(1L, "가게1");
        IntegratedStoreSearchCandidate second = candidate(2L, "가게2");
        IntegratedStoreSearchCandidate third = candidate(3L, "가게3");
        IntegratedStoreSearchQuery scanQuery = IntegratedStoreSearchQuery.from(
                condition, "relevance,desc", null, 3, CURSOR_CODEC);
        String scanCursor = CURSOR_CODEC.encode(
                scanQuery, second.relevanceTier(), second.name(), second.storeId());
        given(repository.search(any()))
                .willReturn(new IntegratedStoreSearchSlice(
                        List.of(first, second), scanCursor))
                .willReturn(new IntegratedStoreSearchSlice(List.of(third), null))
                .willReturn(new IntegratedStoreSearchSlice(
                        List.of(first, second), scanCursor))
                .willReturn(new IntegratedStoreSearchSlice(List.of(third), null));
        given(repository.refreshCurrentlyPublic(any()))
                .willAnswer(invocation -> List.copyOf(invocation.getArgument(0)));
        given(recommendationService.rank(
                org.mockito.ArgumentMatchers.eq(41L),
                any(),
                org.mockito.ArgumentMatchers.same(condition),
                org.mockito.ArgumentMatchers.eq(clock.instant())))
                .willReturn(List.of(
                        ranked(third, 50),
                        ranked(first, 40),
                        ranked(second, 30)));

        var firstPage = service().search(
                41L, "라멘", false, false,
                "recommendation,desc", null, 2);
        var secondPage = service().search(
                41L, "라멘", false, false,
                "recommendation,desc", firstPage.nextCursor(), 2);

        assertThat(firstPage.items()).extracting(item -> item.storeId())
                .containsExactly("3", "1");
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(secondPage.items()).extracting(item -> item.storeId())
                .containsExactly("2");
        assertThat(secondPage.nextCursor()).isNull();
        then(repository).should(times(4)).search(any());
        then(recommendationService).should(times(2)).rank(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.argThat(values -> values.size() == 3),
                org.mockito.ArgumentMatchers.same(condition),
                org.mockito.ArgumentMatchers.eq(clock.instant()));
    }

    private IntegratedStoreSearchService service() {
        return new IntegratedStoreSearchService(
                interpreter,
                repository,
                reservationService,
                candidateLimit,
                CURSOR_CODEC,
                recommendationService,
                clock);
    }

    private static InterpretationResult result(InterpretedSearchCondition condition) {
        return new InterpretationResult("rule-v1", "catalog-v1", condition, List.of());
    }

    private static InterpretedSearchCondition condition(
            LocalDate date,
            LocalTime time,
            Integer partySize,
            String keyword
    ) {
        return new InterpretedSearchCondition(
                List.of(), List.of(), List.of(), List.of(), null,
                partySize, date, time, keyword);
    }

    private static IntegratedStoreSearchCandidate candidate(long id, String name) {
        return new IntegratedStoreSearchCandidate(
                id, name, Region.SEOUL, "서울 중구", "KOREAN",
                OperationStatus.OPEN, true, true, false,
                LocalDateTime.of(2026, 8, 6, 9, 0), 0,
                new BigDecimal("37.500000000000000"),
                new BigDecimal("127.000000000000000"));
    }

    private static RankedRecommendation ranked(
            IntegratedStoreSearchCandidate candidate,
            int totalScore
    ) {
        RecommendationCandidate rankingCandidate = new RecommendationCandidate(
                candidate.storeId(),
                candidate.relevanceTier(),
                false,
                false,
                0,
                0,
                RecommendationAvailability.NOT_REQUESTED,
                null,
                Set.of());
        return new RankedRecommendation(
                rankingCandidate,
                totalScore,
                0,
                totalScore,
                null);
    }
}
