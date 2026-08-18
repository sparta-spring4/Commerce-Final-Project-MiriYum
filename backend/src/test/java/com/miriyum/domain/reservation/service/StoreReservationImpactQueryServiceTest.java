package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.reservation.repository.ReservationRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreReservationImpactQueryServiceTest {

    @Mock
    private ReservationRepository reservations;

    @InjectMocks
    private StoreReservationImpactQueryService service;

    @Test
    void inspectReturnsOnlyRepositoryConfirmedFutureIds() {
        Instant now = Instant.parse("2026-08-16T03:00:00Z");
        given(reservations.findConfirmedFutureIdsByStoreId(7L, now))
                .willReturn(List.of(101L, 102L));

        var impact = service.inspect(7L, now);

        assertThat(impact.storeId()).isEqualTo(7L);
        assertThat(impact.confirmedCount()).isEqualTo(2L);
        assertThat(impact.reservationIds()).containsExactlyInAnyOrder(101L, 102L);
    }
}
