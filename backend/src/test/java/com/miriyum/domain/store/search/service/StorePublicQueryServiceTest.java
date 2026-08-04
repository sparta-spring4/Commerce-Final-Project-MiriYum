package com.miriyum.domain.store.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.PickupEligibility;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.store.schedule.repository.ReservationScheduleVersionRepository;
import com.miriyum.domain.store.schedule.repository.StoreScheduleStateRepository;
import com.miriyum.domain.store.search.dto.ReservationAvailability;
import com.miriyum.domain.store.search.dto.PublicMenu;
import com.miriyum.domain.store.search.model.ReservationSearchCondition;
import com.miriyum.domain.store.search.repository.PublicStoreSnapshot;
import com.miriyum.domain.store.search.repository.StorePublicReadRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorePublicQueryServiceTest {

    @Mock StorePublicReadRepository publicReadRepository;
    @Mock StoreScheduleStateRepository stateRepository;
    @Mock OperatingScheduleVersionRepository operatingRepository;
    @Mock ReservationScheduleVersionRepository reservationRepository;
    @Mock ReservationService reservationService;

    private StorePublicQueryService service;

    @BeforeEach
    void setUp() {
        service = new StorePublicQueryService(
                publicReadRepository, stateRepository,
                operatingRepository, reservationRepository,
                reservationService);
    }

    @Test
    void publicMenusExposeOnlyCurrentPublishedVisibleNonRetiredMenus() {
        PublicMenu visible = publicMenu(11L);
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.of(publicStore(7L)));
        given(publicReadRepository.findPublicMenus(7L)).willReturn(List.of(visible));

        var result = service.getMenus(7L);

        assertThat(result).singleElement().satisfies(menu -> {
            assertThat(menu.menuId()).isEqualTo("11");
            assertThat(menu.name()).isEqualTo("아메리카노");
            assertThat(menu.saleStatus()).isEqualTo(MenuSellingStatus.SELLING);
        });
    }

    @Test
    void publicMenusTreatStoreClosedDuringProjectionAsNotFound() {
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.empty());
        given(publicReadRepository.findPublicMenus(7L)).willReturn(List.of());

        assertThatThrownBy(() -> service.getMenus(7L))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(StoreErrorCode.STORE_NOT_FOUND));
        var order = inOrder(publicReadRepository);
        order.verify(publicReadRepository).findPublicMenus(7L);
        order.verify(publicReadRepository).findPublicStore(7L);
    }

    @Test
    void detailUsesReservationBatchAndFailsClosedOnMismatchedStoreId() {
        given(publicReadRepository.findPublicStore(7L)).willReturn(Optional.of(publicStore(7L)));
        given(publicReadRepository.findPublicMenus(7L)).willReturn(List.of());
        given(stateRepository.findById(7L)).willReturn(Optional.empty());
        given(reservationService.getAvailabilities(org.mockito.ArgumentMatchers.eq(List.of(7L)),
                org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(new ReservationAvailabilityResult(
                        8L, ReservationAvailabilityStatus.AVAILABLE)));

        var result = service.getDetail(
                7L,
                new ReservationSearchCondition(
                        LocalDate.of(2026, 8, 3), LocalTime.of(18, 0), 2),
                true);

        assertThat(result.reservationAvailability())
                .isEqualTo(ReservationAvailability.UNAVAILABLE);
        assertThat(result.operatingHours()).isEmpty();
        assertThat(result.reservationTimeSlots()).isEmpty();
        var order = inOrder(reservationService, publicReadRepository);
        order.verify(reservationService).getAvailabilities(
                org.mockito.ArgumentMatchers.eq(List.of(7L)),
                org.mockito.ArgumentMatchers.any());
        order.verify(publicReadRepository).findPublicMenus(7L);
        order.verify(publicReadRepository).findPublicStore(7L);
    }

    private static PublicStoreSnapshot publicStore(long id) {
        return new PublicStoreSnapshot(
                id, "미리윰", "", Region.SEOUL, "서울 중구", "Asia/Seoul",
                "CAFE_BAKERY", List.of("DATE"), OperationStatus.OPEN,
                PickupEligibility.ELIGIBLE, true, true, true);
    }

    private static PublicMenu publicMenu(long id) {
        return new PublicMenu(
                Long.toString(id), "아메리카노", "", 4500, true, "COFFEE",
                List.of(), List.of(), false, false, MenuSellingStatus.SELLING);
    }
}
