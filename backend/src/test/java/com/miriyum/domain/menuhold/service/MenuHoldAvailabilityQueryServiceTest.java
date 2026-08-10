package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability.AvailabilityStatus;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ResolvedReservationTime;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.store.menu.dto.MenuHoldSelectableMenu;
import com.miriyum.domain.store.menu.service.MenuHoldSelectionQueryService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MenuHoldAvailabilityQueryServiceTest {

    @Mock MenuHoldSelectionQueryService selectionQueryService;
    @Mock ReservationService reservationService;
    @Mock MenuInventoryTransactionService inventoryService;
    @InjectMocks MenuHoldAvailabilityQueryService service;

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
        given(inventoryService.findOnlineAvailability(org.mockito.ArgumentMatchers.any()))
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
}
