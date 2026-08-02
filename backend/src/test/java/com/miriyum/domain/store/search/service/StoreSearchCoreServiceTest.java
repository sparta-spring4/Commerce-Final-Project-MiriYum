package com.miriyum.domain.store.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.search.dto.ReservationAvailability;
import com.miriyum.domain.store.search.dto.PublicStoreSummary;
import com.miriyum.domain.store.search.model.StoreSearchQuery;
import com.miriyum.domain.store.search.repository.StoreSearchCandidate;
import com.miriyum.domain.store.search.repository.StoreSearchRepository;
import com.miriyum.domain.store.service.CatalogKind;
import com.miriyum.domain.store.service.CatalogService;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
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

    private StoreSearchCoreService service;

    @BeforeEach
    void setUp() {
        service = new StoreSearchCoreService(
                new StoreSearchCatalogPolicy(catalogService), repository);
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
                LocalDateTime.of(2026, 8, 2, 9, 0));
        given(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, "KOREAN"))
                .willReturn(true);
        given(repository.search(query)).willReturn(new PageImpl<>(
                List.of(candidate), PageRequest.of(2, 20), 41));

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
}
