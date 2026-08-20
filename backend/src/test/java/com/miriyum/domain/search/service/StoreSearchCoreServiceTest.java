package com.miriyum.domain.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;

import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.search.config.StoreSearchCandidateLimit;
import com.miriyum.domain.search.dto.publicapi.ReservationAvailability;
import com.miriyum.domain.search.dto.publicapi.PublicStoreSummary;
import com.miriyum.domain.search.model.StoreSearchQuery;
import com.miriyum.domain.search.repository.StoreSearchCandidate;
import com.miriyum.domain.search.repository.StoreSearchRepository;
import com.miriyum.domain.store.service.CatalogKind;
import com.miriyum.domain.store.service.CatalogService;
import com.miriyum.domain.reservation.dto.request.ReservationAvailabilityCondition;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.global.exception.ServiceException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class StoreSearchCoreServiceTest {

    @Mock
    private CatalogService catalogService;

    @Mock
    private StoreSearchRepository repository;

    @Mock
    private ReservationService reservationService;

    private StoreSearchCoreService service;

    @BeforeEach
    void setUp() {
        service = new StoreSearchCoreService(
                new StoreSearchCatalogPolicy(catalogService),
                repository,
                reservationService,
                new StoreSearchCandidateLimit(5_000));
    }

    @Test
    @DisplayName("후보 매장을 공개 요약과 NOT_REQUESTED 가용성으로 투영한다")
    void mapsValidatedCandidatesToNotRequestedSummaries() {
        // given
        StoreSearchQuery query = queryWithoutReservation("KOREAN");
        StoreSearchCandidate candidate = new StoreSearchCandidate(
                9_007_199_254_740_993L,
                "성수 키친",
                Region.SEOUL,
                "서울 성동구",
                "KOREAN",
                OperationStatus.OPEN,
                true,
                false,
                true,
                LocalDateTime.of(2026, 8, 2, 9, 0),
                new BigDecimal("37.566500000000000"),
                new BigDecimal("126.978000000000000"));
        given(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, "KOREAN"))
                .willReturn(true);
        given(repository.search(query)).willReturn(new PageImpl<>(
                List.of(candidate), PageRequest.of(2, 20), 41));
        given(repository.refreshCurrentlyPublic(List.of(candidate)))
                .willReturn(List.of(candidate));

        // when
        Page<PublicStoreSummary> result = service.searchWithoutAvailability(query);

        // then
        assertThat(result.getNumber()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getTotalElements()).isEqualTo(41);
        assertThat(result.getContent()).singleElement().satisfies(summary -> {
            assertThat(summary.storeId()).isEqualTo("9007199254740993");
            assertThat(summary.name()).isEqualTo("성수 키친");
            assertThat(summary.region()).isEqualTo(Region.SEOUL);
            assertThat(summary.address()).isEqualTo("서울 성동구");
            assertThat(summary.storeCategoryCode()).isEqualTo("KOREAN");
            assertThat(summary.operationStatus()).isEqualTo(OperationStatus.OPEN);
            assertThat(summary.modes().reservationEnabled()).isTrue();
            assertThat(summary.modes().menuHoldEnabled()).isFalse();
            assertThat(summary.modes().pickupEnabled()).isTrue();
            assertThat(summary.reservationAvailability())
                    .isEqualTo(ReservationAvailability.NOT_REQUESTED);
            assertThat(summary.coordinates()).isNotNull();
            assertThat(summary.coordinates().latitude())
                    .isEqualByComparingTo("37.566500000000000");
            assertThat(summary.coordinates().longitude())
                    .isEqualByComparingTo("126.978000000000000");
        });
    }

    @Test
    @DisplayName("2단계 계약 전에는 예약 조건이 있는 검색 실행을 거절한다")
    void refusesReservationConditionUntilPhaseTwoContractExists() {
        // given
        StoreSearchQuery query = StoreSearchQuery.from(
                null,
                null,
                null,
                LocalDate.of(2026, 8, 3),
                LocalTime.of(18, 0),
                4,
                false,
                null,
                0,
                20);
        lenient().when(repository.search(query))
                .thenThrow(new AssertionError("예약 조건은 저장소 조회 전에 거절해야 한다"));

        // when & then
        assertThatThrownBy(() -> service.searchWithoutAvailability(query))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void composesReservationBatchInCandidateOrderAndPreservesInfantMeaning() {
        StoreSearchQuery query = reservationQuery(false);
        List<StoreSearchCandidate> candidates = List.of(candidate(7L), candidate(3L));
        given(repository.search(query)).willReturn(new PageImpl<>(
                candidates, PageRequest.of(0, 20), 2));
        given(repository.refreshCurrentlyPublic(candidates)).willReturn(candidates);
        given(reservationService.getAvailabilities(
                eq(List.of(7L, 3L)), any(ReservationAvailabilityCondition.class)))
                .willReturn(List.of(
                        new ReservationAvailabilityResult(
                                7L, ReservationAvailabilityStatus.AVAILABLE),
                        new ReservationAvailabilityResult(
                                3L, ReservationAvailabilityStatus.UNAVAILABLE)));

        Page<PublicStoreSummary> result = service.search(query, true);

        assertThat(result.getContent())
                .extracting(PublicStoreSummary::storeId,
                        PublicStoreSummary::reservationAvailability)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("7", ReservationAvailability.AVAILABLE),
                        org.assertj.core.groups.Tuple.tuple("3", ReservationAvailability.UNAVAILABLE));
        then(reservationService).should().getAvailabilities(
                eq(List.of(7L, 3L)),
                eq(new ReservationAvailabilityCondition(
                        LocalDate.of(2026, 8, 3), LocalTime.of(18, 0), null, 4, true)));
    }

    @Test
    void failsClosedWhenReservationBatchDoesNotPreserveCandidateOrder() {
        StoreSearchQuery query = reservationQuery(false);
        given(repository.search(query)).willReturn(new PageImpl<>(
                List.of(candidate(7L), candidate(3L)), PageRequest.of(0, 20), 2));
        List<StoreSearchCandidate> candidates = List.of(candidate(7L), candidate(3L));
        given(repository.refreshCurrentlyPublic(candidates)).willReturn(candidates);
        given(reservationService.getAvailabilities(eq(List.of(7L, 3L)), any()))
                .willReturn(List.of(
                        new ReservationAvailabilityResult(
                                3L, ReservationAvailabilityStatus.AVAILABLE),
                        new ReservationAvailabilityResult(
                                7L, ReservationAvailabilityStatus.AVAILABLE)));

        Page<PublicStoreSummary> result = service.search(query, false);

        assertThat(result.getContent())
                .extracting(PublicStoreSummary::reservationAvailability)
                .containsOnly(ReservationAvailability.UNAVAILABLE);
    }

    @Test
    void removesStoreThatIsNoLongerPublicBeforeReturningResponse() {
        StoreSearchQuery query = reservationQuery(false);
        given(repository.search(query)).willReturn(new PageImpl<>(
                List.of(candidate(7L), candidate(3L)), PageRequest.of(0, 20), 2));
        List<StoreSearchCandidate> candidates = List.of(candidate(7L), candidate(3L));
        given(repository.refreshCurrentlyPublic(candidates))
                .willReturn(List.of(candidate(3L)));
        given(repository.refreshCurrentlyPublic(List.of(candidate(3L))))
                .willReturn(List.of(candidate(3L)));
        given(reservationService.getAvailabilities(eq(List.of(3L)), any()))
                .willReturn(List.of(new ReservationAvailabilityResult(
                        3L, ReservationAvailabilityStatus.AVAILABLE)));

        Page<PublicStoreSummary> result = service.search(query, false);

        assertThat(result.getContent()).extracting(PublicStoreSummary::storeId)
                .containsExactly("3");
    }

    @Test
    void availableOnlyFiltersBeforePagingAndReportsExactTotal() {
        StoreSearchQuery query = StoreSearchQuery.from(
                null, null, null,
                LocalDate.of(2026, 8, 3), LocalTime.of(18, 0), 4,
                true, "name,asc", 1, 1);
        List<StoreSearchCandidate> candidates =
                List.of(candidate(1L), candidate(2L), candidate(3L));
        given(repository.searchAll(query, 5_000)).willReturn(candidates);
        given(repository.refreshCurrentlyPublic(candidates)).willReturn(candidates);
        given(reservationService.getAvailabilities(eq(List.of(1L, 2L, 3L)), any()))
                .willReturn(List.of(
                        new ReservationAvailabilityResult(1L, ReservationAvailabilityStatus.AVAILABLE),
                        new ReservationAvailabilityResult(2L, ReservationAvailabilityStatus.UNAVAILABLE),
                        new ReservationAvailabilityResult(3L, ReservationAvailabilityStatus.AVAILABLE)));

        Page<PublicStoreSummary> result = service.search(query, false);

        assertThat(result.getContent()).extracting(PublicStoreSummary::storeId)
                .containsExactly("3");
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getTotalPages()).isEqualTo(2);
        then(repository).should().searchAll(query, 5_000);
    }

    @Test
    void availableOnlyScansInBoundedReservationBatches() {
        StoreSearchQuery query = StoreSearchQuery.from(
                null, null, null,
                LocalDate.of(2026, 8, 3), LocalTime.of(18, 0), 4,
                true, "name,asc", 0, 1);
        List<StoreSearchCandidate> first = java.util.stream.LongStream.rangeClosed(1, 200)
                .mapToObj(this::candidate).toList();
        List<StoreSearchCandidate> second = List.of(candidate(201L));
        List<StoreSearchCandidate> all = new ArrayList<>(first);
        all.addAll(second);
        given(repository.searchAll(query, 5_000)).willReturn(all);
        given(repository.refreshCurrentlyPublic(first)).willReturn(first);
        given(repository.refreshCurrentlyPublic(second)).willReturn(second);
        given(reservationService.getAvailabilities(any(), any())).willAnswer(invocation ->
                ((List<Long>) invocation.getArgument(0)).stream()
                        .map(id -> new ReservationAvailabilityResult(
                                id, ReservationAvailabilityStatus.AVAILABLE))
                        .toList());

        Page<PublicStoreSummary> result = service.search(query, false);

        assertThat(result.getContent()).extracting(PublicStoreSummary::storeId)
                .containsExactly("1");
        assertThat(result.getTotalElements()).isEqualTo(201);
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        then(reservationService).should(times(2)).getAvailabilities(ids.capture(), any());
        assertThat(ids.getAllValues()).allSatisfy(batch ->
                assertThat(batch).hasSizeLessThanOrEqualTo(
                        StoreSearchCoreService.AVAILABILITY_BATCH_SIZE));
    }

    @Test
    void reprojectsLatestSafetyStateAfterReservationBatch() {
        StoreSearchQuery query = reservationQuery(false);
        StoreSearchCandidate before = candidate(7L);
        StoreSearchCandidate latest = new StoreSearchCandidate(
                7L, before.name(), before.region(), before.address(),
                before.storeCategoryCode(),
                OperationStatus.TEMPORARILY_CLOSED, false, false, true,
                before.createdAt());
        given(repository.search(query)).willReturn(new PageImpl<>(
                List.of(before), PageRequest.of(0, 20), 1));
        given(repository.refreshCurrentlyPublic(List.of(before))).willReturn(List.of(before));
        given(repository.refreshCurrentlyPublic(List.of(before))).willReturn(
                List.of(before), List.of(latest));
        given(reservationService.getAvailabilities(eq(List.of(7L)), any()))
                .willReturn(List.of(new ReservationAvailabilityResult(
                        7L, ReservationAvailabilityStatus.AVAILABLE)));

        Page<PublicStoreSummary> result = service.search(query, false);

        assertThat(result.getContent()).singleElement().satisfies(summary -> {
            assertThat(summary.name()).isEqualTo(before.name());
            assertThat(summary.address()).isEqualTo(before.address());
            assertThat(summary.operationStatus()).isEqualTo(OperationStatus.TEMPORARILY_CLOSED);
            assertThat(summary.modes().reservationEnabled()).isFalse();
            assertThat(summary.reservationAvailability())
                    .isEqualTo(ReservationAvailability.UNAVAILABLE);
        });
    }

    @Test
    @DisplayName("비활성 카테고리 필터를 STORE_004로 거절한다")
    void rejectsInactiveCategoryThroughRealPolicy() {
        // given
        StoreSearchQuery query = queryWithoutReservation("UNKNOWN");
        given(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, "UNKNOWN"))
                .willReturn(false);
        lenient().when(repository.search(query))
                .thenThrow(new AssertionError("비활성 카테고리는 저장소 조회 전에 거절해야 한다"));

        // when & then
        assertThatThrownBy(() -> service.searchWithoutAvailability(query))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.CATALOG_CODE_INVALID));
    }

    private StoreSearchQuery queryWithoutReservation(String categoryCode) {
        return StoreSearchQuery.from(
                null,
                null,
                categoryCode,
                null,
                null,
                null,
                false,
                null,
                2,
                20);
    }

    private StoreSearchQuery reservationQuery(boolean availableOnly) {
        return StoreSearchQuery.from(
                null, null, null,
                LocalDate.of(2026, 8, 3), LocalTime.of(18, 0), 4,
                availableOnly, "name,asc", 0, 20);
    }

    private StoreSearchCandidate candidate(long storeId) {
        return new StoreSearchCandidate(
                storeId, "매장 " + storeId, Region.SEOUL, "서울",
                "KOREAN", OperationStatus.OPEN, true, true, true,
                LocalDateTime.of(2026, 8, 2, 9, 0));
    }
}
