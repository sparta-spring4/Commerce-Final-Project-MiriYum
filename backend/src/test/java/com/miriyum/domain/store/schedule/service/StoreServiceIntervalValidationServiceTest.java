package com.miriyum.domain.store.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import com.miriyum.domain.store.closure.repository.*;
import com.miriyum.domain.store.core.repository.StoreRepository;
import com.miriyum.domain.store.schedule.dto.*;
import com.miriyum.domain.store.schedule.repository.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreServiceIntervalValidationServiceTest {
    @Mock StoreRepository stores; @Mock StoreScheduleStateRepository states;
    @Mock OperatingScheduleVersionRepository operating; @Mock ReservationScheduleVersionRepository reservation;
    @Mock RegularClosureVersionRepository regular; @Mock TemporaryClosureRepository temporary;

    @Test void preservesOrderAndDuplicatesWhileMissingSourcesFailClosed() {
        StoreServiceIntervalValidationService service = new StoreServiceIntervalValidationService(
                stores, states, operating, reservation, regular, temporary);
        var first = request(2); var second = request(1);
        given(stores.findAllById(org.mockito.ArgumentMatchers.any())).willReturn(List.of());
        given(states.findAllByStoreIdIn(org.mockito.ArgumentMatchers.any())).willReturn(List.of());
        given(temporary.findOverlapping(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).willReturn(List.of());

        List<StoreServiceIntervalResult> result = service.validateServiceIntervals(List.of(first, second, first));
        assertThat(result).extracting(StoreServiceIntervalResult::storeId).containsExactly(2L, 1L, 2L);
        assertThat(result).extracting(StoreServiceIntervalResult::status)
                .containsOnly(StoreServiceIntervalStatus.NOT_ACCEPTING);
    }

    private StoreServiceIntervalRequest request(long storeId) {
        return new StoreServiceIntervalRequest(storeId, Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"));
    }
}
