package com.miriyum.domain.alternative.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.miriyum.domain.alternative.model.MenuAlternativeMode;
import com.miriyum.domain.alternative.model.MenuAlternativeSearchCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.service.MenuInventoryTransactionService;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.dto.response.ResolvedReservationTime;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.reservation.service.ReservationTimeResolutionService;
import com.miriyum.domain.search.dto.contract.MenuAlternativeCandidateView;
import com.miriyum.domain.search.dto.contract.MenuAlternativeSourceView;
import com.miriyum.domain.search.service.MenuAlternativeCandidateQueryService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MenuAlternativeSearchServiceTest {
    @Mock MenuAlternativeCandidateQueryService candidateQuery;
    @Mock ReservationService reservationService;
    @Mock ReservationTimeResolutionService reservationTimeResolutionService;
    @Mock MenuInventoryTransactionService inventoryService;
    private MenuAlternativeSearchService service;

    @BeforeEach
    void setUp() {
        service = new MenuAlternativeSearchService(candidateQuery, reservationService,
                reservationTimeResolutionService, inventoryService,
                new MenuAlternativeEligibility());
    }

    @Test
    void returnsOnlySameStoreAlternativesAndDoesNotQueryNearby() {
        var source = source(null, null);
        given(candidateQuery.findSource(1L, 10L)).willReturn(source);
        given(reservationTimeResolutionService.resolveReservationTimes(any(), any()))
                .willReturn(List.of(ReservationTimeResolutionResult.resolved(1L, resolved(1L))));
        given(candidateQuery.findSameStoreCandidates(source)).willReturn(List.of(candidate(11L)));
        given(reservationService.getAvailabilities(any(), any())).willReturn(List.of(
                new ReservationAvailabilityResult(1L, ReservationAvailabilityStatus.AVAILABLE)));
        given(inventoryService.findExistingOnlineAvailability(any())).willReturn(List.of(
                new MenuInventoryAvailability(11L, 1L, "Asia/Seoul",
                        LocalDate.of(2026, 8, 15), LocalTime.of(18, 30),
                        LocalDate.of(2026, 8, 15), LocalTime.of(20, 0), 3,
                        MenuInventoryAvailability.AvailabilityStatus.AVAILABLE)));

        var result = service.search(1L, 10L, command());

        assertThat(result.mode()).isEqualTo(MenuAlternativeMode.SAME_STORE);
        assertThat(result.items()).extracting(item -> item.menuId()).containsExactly(11L);
        then(candidateQuery).should(never()).findNearbyCandidates(any(), any());
    }

    @Test
    void skipsSameStoreInventoryWhenPartyAvailabilityIsUnavailable() {
        var source = source(null, null);
        given(candidateQuery.findSource(1L, 10L)).willReturn(source);
        given(reservationTimeResolutionService.resolveReservationTimes(any(), any()))
                .willReturn(List.of(ReservationTimeResolutionResult.resolved(1L, resolved(1L))));
        given(candidateQuery.findSameStoreCandidates(source)).willReturn(List.of(candidate(11L)));
        given(reservationService.getAvailabilities(any(), any())).willReturn(List.of(
                new ReservationAvailabilityResult(1L, ReservationAvailabilityStatus.UNAVAILABLE)));

        var result = service.search(1L, 10L, command());

        assertThat(result.mode()).isEqualTo(MenuAlternativeMode.REGION_SELECTION_REQUIRED);
        assertThat(result.items()).isEmpty();
        then(inventoryService).should(never()).findExistingOnlineAvailability(any());
    }

    @Test
    void rejectsMismatchedSameStoreAvailabilityResponse() {
        var source = source(null, null);
        given(candidateQuery.findSource(1L, 10L)).willReturn(source);
        given(reservationTimeResolutionService.resolveReservationTimes(any(), any()))
                .willReturn(List.of(ReservationTimeResolutionResult.resolved(1L, resolved(1L))));
        given(candidateQuery.findSameStoreCandidates(source)).willReturn(List.of(candidate(11L)));
        given(reservationService.getAvailabilities(any(), any())).willReturn(List.of(
                new ReservationAvailabilityResult(2L, ReservationAvailabilityStatus.AVAILABLE)));

        assertThatThrownBy(() -> service.search(1L, 10L, command()))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void returnsRegionSelectionRequiredWhenNoSameStoreResultAndNoVerifiedCoordinates() {
        var source = source(null, null);
        given(candidateQuery.findSource(1L, 10L)).willReturn(source);
        given(reservationTimeResolutionService.resolveReservationTimes(any(), any()))
                .willReturn(List.of(ReservationTimeResolutionResult.resolved(1L, resolved(1L))));
        given(candidateQuery.findSameStoreCandidates(source)).willReturn(List.of());

        var result = service.search(1L, 10L, command());

        assertThat(result.mode()).isEqualTo(MenuAlternativeMode.REGION_SELECTION_REQUIRED);
        assertThat(result.items()).isEmpty();
    }

    @Test
    void fallsBackToReservationAndInventoryRevalidatedNearbyStore() {
        var source = source(new java.math.BigDecimal("37.500000"),
                new java.math.BigDecimal("127.000000"));
        given(candidateQuery.findSource(1L, 10L)).willReturn(source);
        given(reservationTimeResolutionService.resolveReservationTimes(any(), any()))
                .willReturn(List.of(ReservationTimeResolutionResult.resolved(1L, resolved(1L))))
                .willReturn(List.of(ReservationTimeResolutionResult.resolved(2L, resolved(2L))));
        given(candidateQuery.findSameStoreCandidates(source)).willReturn(List.of());
        given(candidateQuery.findNearbyCandidates(any(), any()))
                .willReturn(List.of(new MenuAlternativeCandidateView(2L, "근처", 21L, "대안",
                        10_000, "MAIN", List.of("A"), "REGISTERED", List.of(),
                        new java.math.BigDecimal("37.501000"),
                        new java.math.BigDecimal("127.000000"))));
        given(reservationService.getAvailabilities(any(), any())).willReturn(List.of(
                new ReservationAvailabilityResult(2L, ReservationAvailabilityStatus.AVAILABLE)));
        given(inventoryService.findExistingOnlineAvailability(any())).willReturn(List.of(
                new MenuInventoryAvailability(21L, 1L, "Asia/Seoul",
                        LocalDate.of(2026, 8, 15), LocalTime.of(18, 30),
                        LocalDate.of(2026, 8, 15), LocalTime.of(20, 0), 2,
                        MenuInventoryAvailability.AvailabilityStatus.AVAILABLE)));

        var result = service.search(1L, 10L, command());

        assertThat(result.mode()).isEqualTo(MenuAlternativeMode.NEARBY_STORE);
        assertThat(result.items()).extracting(item -> item.storeId()).containsExactly(2L);
        assertThat(result.items().getFirst().distanceMeters()).isPositive();
    }

    @Test
    void batchesNearbyTimeResolutionAndInventoryForSharedServiceWindow() {
        var source = source(new java.math.BigDecimal("37.500000"),
                new java.math.BigDecimal("127.000000"));
        given(candidateQuery.findSource(1L, 10L)).willReturn(source);
        given(reservationTimeResolutionService.resolveReservationTimes(any(), any()))
                .willAnswer(invocation -> {
                    List<Long> storeIds = invocation.getArgument(0);
                    return storeIds.stream()
                            .map(storeId -> ReservationTimeResolutionResult.resolved(
                                    storeId, resolved(storeId)))
                            .toList();
                });
        given(candidateQuery.findSameStoreCandidates(source)).willReturn(List.of());
        given(candidateQuery.findNearbyCandidates(any(), any())).willReturn(List.of(
                nearbyCandidate(2L, 21L, "37.501000"),
                nearbyCandidate(3L, 31L, "37.502000")));
        given(reservationService.getAvailabilities(any(), any()))
                .willAnswer(invocation -> {
                    List<Long> storeIds = invocation.getArgument(0);
                    return storeIds.stream()
                            .map(storeId -> new ReservationAvailabilityResult(
                                    storeId, ReservationAvailabilityStatus.AVAILABLE))
                            .toList();
                });
        given(inventoryService.findExistingOnlineAvailability(any()))
                .willAnswer(invocation -> {
                    com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityQuery query =
                            invocation.getArgument(0);
                    return query.menuIds().stream()
                            .map(menuId -> new MenuInventoryAvailability(
                                    menuId, 1L, "Asia/Seoul",
                                    LocalDate.of(2026, 8, 15), LocalTime.of(18, 30),
                                    LocalDate.of(2026, 8, 15), LocalTime.of(20, 0), 3,
                                    MenuInventoryAvailability.AvailabilityStatus.AVAILABLE))
                            .toList();
                });

        var result = service.search(1L, 10L, command());

        assertThat(result.items()).extracting(item -> item.menuId())
                .containsExactlyInAnyOrder(21L, 31L);
        then(reservationTimeResolutionService).should(times(2))
                .resolveReservationTimes(any(), any());
        then(reservationTimeResolutionService).should()
                .resolveReservationTimes(eq(List.of(2L, 3L)), any());
        then(inventoryService).should(times(1))
                .findExistingOnlineAvailability(eq(
                        new com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityQuery(
                                List.of(21L, 31L),
                                LocalDate.of(2026, 8, 15), LocalTime.of(18, 30),
                                LocalDate.of(2026, 8, 15), LocalTime.of(20, 0))));
    }

    private static MenuAlternativeSearchCommand command() {
        return new MenuAlternativeSearchCommand(2, LocalDate.of(2026, 8, 15),
                LocalTime.of(18, 30), ZoneOffset.ofHours(9), 2, false, Set.of(), 10);
    }

    private static MenuAlternativeSourceView source(java.math.BigDecimal lat,
            java.math.BigDecimal lon) {
        return new MenuAlternativeSourceView(1L, "원본", 10L, "원본메뉴", 10_000,
                "MAIN", List.of("A"), "REGISTERED", List.of(), lat, lon);
    }

    private static MenuAlternativeCandidateView candidate(long menuId) {
        return new MenuAlternativeCandidateView(1L, "원본", menuId, "대안", 10_000,
                "MAIN", List.of("A"), "REGISTERED", List.of(), null, null);
    }

    private static MenuAlternativeCandidateView nearbyCandidate(long storeId, long menuId,
            String latitude) {
        return new MenuAlternativeCandidateView(storeId, "근처 " + storeId, menuId,
                "대안 " + menuId, 10_000, "MAIN", List.of("A"), "REGISTERED", List.of(),
                new java.math.BigDecimal(latitude), new java.math.BigDecimal("127.000000"));
    }

    private static ResolvedReservationTime resolved(long storeId) {
        return new ResolvedReservationTime(LocalDate.of(2026, 8, 15),
                Instant.parse("2026-08-15T09:30:00Z"), Instant.parse("2026-08-15T11:00:00Z"),
                Instant.parse("2026-08-15T11:00:00Z"), "Asia/Seoul", 32400, 32400,
                32400, 30, 90, 0, storeId, 1L);
    }
}
