package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ResolvedReservationTime;
import com.miriyum.domain.reservation.service.ReservationService;
import com.miriyum.domain.store.menu.service.MenuHoldSelectionQueryService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
@Tag("integration-shard-a")
class MenuHoldAvailabilityIT {

    @Test
    void emptyCandidateListStillUsesReservationPolicyAndSkipsInventory() {
        var selection = mock(MenuHoldSelectionQueryService.class);
        var reservation = mock(ReservationService.class);
        var inventory = mock(MenuInventoryTransactionService.class);
        var service = new MenuHoldAvailabilityQueryService(selection, reservation, inventory);
        LocalDate date = LocalDate.of(2026, 8, 10);
        var time = new ResolvedReservationTime(date,
                Instant.parse("2026-08-10T09:00:00Z"), Instant.parse("2026-08-10T10:00:00Z"),
                Instant.parse("2026-08-10T10:15:00Z"), "Asia/Seoul",
                32400, 32400, 32400, 30, 60, 15, 7L, 1L);
        given(selection.findSelectableMenus(7L)).willReturn(List.of());
        given(reservation.resolveReservationTimes(org.mockito.ArgumentMatchers.eq(List.of(7L)),
                org.mockito.ArgumentMatchers.any())).willReturn(List.of(
                        ReservationTimeResolutionResult.resolved(7L, time)));

        var result = service.findAvailability(7L, date, LocalTime.of(18, 0), null);

        assertThat(result.items()).isEmpty();
        assertThat(result.serviceEndAt().toString()).isEqualTo("2026-08-10T19:00+09:00");
        then(inventory).shouldHaveNoInteractions();
    }
}
