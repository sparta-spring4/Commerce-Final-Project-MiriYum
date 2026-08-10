package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability.AvailabilityStatus;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ResolvedReservationTime;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.menu.dto.contract.MenuHoldSelectableMenu;
import com.miriyum.domain.menu.service.MenuHoldSelectionQueryService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MenuHoldAvailabilityQueryServiceTest {

    @Mock MenuHoldSelectionQueryService selectionQueryService;
    @Mock ReservationService reservationService;
    @Mock MenuInventoryTransactionService inventoryService;
    @InjectMocks MenuHoldAvailabilityQueryService service;

    @Test
    void preservesReservationUnavailableError() {
        LocalDate date = LocalDate.of(2026, 8, 10);
        given(selectionQueryService.findSelectableMenus(7L)).willReturn(List.of());
        given(reservationService.resolveReservationTimes(
                List.of(7L), new com.miriyum.domain.reservation.dto.request.ReservationTimeRequest(
                        date, LocalTime.of(18, 0), null)))
                .willReturn(List.of(ReservationTimeResolutionResult.unavailable(7L)));

        assertThatThrownBy(() -> service.findAvailability(
                7L, date, LocalTime.of(18, 0), null))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.OUTSIDE_RESERVATION_WINDOW));
        then(inventoryService).shouldHaveNoInteractions();
    }

    @Test
    void failsClosedWhenReservationPolicyBelongsToAnotherStore() {
        LocalDate date = LocalDate.of(2026, 8, 10);
        ResolvedReservationTime malformed = resolvedTime(
                date, "2026-08-10T09:00:00Z", "2026-08-10T10:00:00Z", 8L);
        given(selectionQueryService.findSelectableMenus(7L)).willReturn(List.of());
        given(reservationService.resolveReservationTimes(
                List.of(7L), new com.miriyum.domain.reservation.dto.request.ReservationTimeRequest(
                        date, LocalTime.of(18, 0), null)))
                .willReturn(List.of(ReservationTimeResolutionResult.resolved(7L, malformed)));

        assertThatThrownBy(() -> service.findAvailability(
                7L, date, LocalTime.of(18, 0), null))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void preservesSoldOutAndOmitsCandidateWithoutCurrentBucket() {
        LocalDate date = LocalDate.of(2026, 8, 10);
        ResolvedReservationTime time = resolvedTime(
                date, "2026-08-10T09:00:00Z", "2026-08-10T10:00:00Z", 7L);
        given(selectionQueryService.findSelectableMenus(7L)).willReturn(List.of(
                new MenuHoldSelectableMenu(11L, "Coffee", 4500),
                new MenuHoldSelectableMenu(12L, "Cake", 9000)));
        given(reservationService.resolveReservationTimes(
                org.mockito.ArgumentMatchers.eq(List.of(7L)),
                org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(ReservationTimeResolutionResult.resolved(7L, time)));
        given(inventoryService.findExistingOnlineAvailability(
                org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(new MenuInventoryAvailability(
                        12L, 2L, "Asia/Seoul", date, LocalTime.of(18, 0),
                        date, LocalTime.of(19, 0), 0, AvailabilityStatus.SOLD_OUT)));

        var result = service.findAvailability(7L, date, LocalTime.of(18, 0), null);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.menuId()).isEqualTo("12");
            assertThat(item.availableOnlineQuantity()).isZero();
            assertThat(item.availabilityStatus()).isEqualTo(AvailabilityStatus.SOLD_OUT);
        });
    }

    @Test
    void preservesOvernightDatesInInventoryQuery() {
        LocalDate date = LocalDate.of(2026, 8, 10);
        ResolvedReservationTime time = resolvedTime(
                date, "2026-08-10T14:30:00Z", "2026-08-10T15:30:00Z", 7L);
        given(selectionQueryService.findSelectableMenus(7L)).willReturn(List.of(
                new MenuHoldSelectableMenu(11L, "Coffee", 4500)));
        given(reservationService.resolveReservationTimes(
                org.mockito.ArgumentMatchers.eq(List.of(7L)),
                org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(ReservationTimeResolutionResult.resolved(7L, time)));
        given(inventoryService.findExistingOnlineAvailability(
                org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of());

        service.findAvailability(7L, date, LocalTime.of(23, 30), null);

        ArgumentCaptor<com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityQuery> captor =
                ArgumentCaptor.forClass(
                        com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityQuery.class);
        then(inventoryService).should().findExistingOnlineAvailability(captor.capture());
        assertThat(captor.getValue().serviceDate()).isEqualTo(date);
        assertThat(captor.getValue().startTime()).isEqualTo(LocalTime.of(23, 30));
        assertThat(captor.getValue().endDate()).isEqualTo(date.plusDays(1));
        assertThat(captor.getValue().endTime()).isEqualTo(LocalTime.of(0, 30));
    }

    @Test
    void failsClosedWhenResolvedOffsetDoesNotMatchTimeZone() {
        LocalDate date = LocalDate.of(2026, 8, 10);
        ResolvedReservationTime malformed = new ResolvedReservationTime(
                date, Instant.parse("2026-08-10T09:00:00Z"),
                Instant.parse("2026-08-10T10:00:00Z"), Instant.parse("2026-08-10T10:15:00Z"),
                "Asia/Seoul", 0, 0, 0, 30, 60, 15, 7L, 1L);
        given(selectionQueryService.findSelectableMenus(7L)).willReturn(List.of());
        given(reservationService.resolveReservationTimes(
                List.of(7L), new com.miriyum.domain.reservation.dto.request.ReservationTimeRequest(
                        date, LocalTime.of(18, 0), null)))
                .willReturn(List.of(ReservationTimeResolutionResult.resolved(7L, malformed)));

        assertThatThrownBy(() -> service.findAvailability(
                7L, date, LocalTime.of(18, 0), null))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void returnsOnlyCurrentBucketsSortedWithServerResolvedOffsetTimes() {
        LocalDate date = LocalDate.of(2026, 8, 10);
        ResolvedReservationTime time = new ResolvedReservationTime(
                date, Instant.parse("2026-08-10T09:00:00Z"),
                Instant.parse("2026-08-10T10:30:00Z"), Instant.parse("2026-08-10T10:45:00Z"),
                "Asia/Seoul", 32400, 32400, 32400, 30, 90, 15, 7L, 3L);
        given(selectionQueryService.findSelectableMenus(7L)).willReturn(List.of(
                new MenuHoldSelectableMenu(12L, "Cake", 9000),
                new MenuHoldSelectableMenu(11L, "Coffee", 4500)));
        given(reservationService.resolveReservationTimes(
                List.of(7L), new com.miriyum.domain.reservation.dto.request.ReservationTimeRequest(
                        date, LocalTime.of(18, 0), ZoneOffset.ofHours(9))))
                .willReturn(List.of(ReservationTimeResolutionResult.resolved(7L, time)));
        given(inventoryService.findExistingOnlineAvailability(
                org.mockito.ArgumentMatchers.any()))
                .willReturn(List.of(new MenuInventoryAvailability(
                        11L, 5L, "Asia/Seoul", date, LocalTime.of(18, 0),
                        date, LocalTime.of(19, 30), 4, AvailabilityStatus.AVAILABLE)));

        var result = service.findAvailability(
                7L, date, LocalTime.of(18, 0), ZoneOffset.ofHours(9));

        assertThat(result.serviceDate()).isEqualTo(date);
        assertThat(result.startAt()).isEqualTo(OffsetDateTime.parse("2026-08-10T18:00+09:00"));
        assertThat(result.serviceEndAt()).isEqualTo(OffsetDateTime.parse("2026-08-10T19:30+09:00"));
        assertThat(result.timeZoneId()).isEqualTo("Asia/Seoul");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.menuId()).isEqualTo("11");
            assertThat(item.menuName()).isEqualTo("Coffee");
            assertThat(item.availableOnlineQuantity()).isEqualTo(4);
            assertThat(item.availabilityStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);
        });
    }

    private static ResolvedReservationTime resolvedTime(
            LocalDate date,
            String startAt,
            String serviceEndAt,
            long policyStoreId
    ) {
        Instant end = Instant.parse(serviceEndAt);
        return new ResolvedReservationTime(
                date, Instant.parse(startAt), end, end.plusSeconds(900),
                "Asia/Seoul", 32400, 32400, 32400,
                30, 60, 15, policyStoreId, 1L);
    }
}
