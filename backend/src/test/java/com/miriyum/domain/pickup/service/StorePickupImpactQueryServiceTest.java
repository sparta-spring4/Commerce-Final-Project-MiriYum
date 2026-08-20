package com.miriyum.domain.pickup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.pickup.entity.PickupStatus;
import com.miriyum.domain.pickup.repository.PickupReservationRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorePickupImpactQueryServiceTest {

    @Mock
    private PickupReservationRepository pickups;

    @InjectMocks
    private StorePickupImpactQueryService service;

    @Test
    void inspectCountsOnlyFutureConfirmedPickupsForTargetStore() {
        Instant now = Instant.parse("2026-08-16T03:00:00Z");
        given(pickups.findIdsByStoreIdAndStatusAndPickupAtGreaterThanEqual(
                7L, PickupStatus.CONFIRMED, now)).willReturn(List.of(21L, 22L));

        var impact = service.inspect(7L, now);

        assertThat(impact.storeId()).isEqualTo(7L);
        assertThat(impact.confirmedCount()).isEqualTo(2L);
        assertThat(impact.pickupIds()).containsExactlyInAnyOrder(21L, 22L);
    }
}
