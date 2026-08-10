package com.miriyum.domain.store.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import com.miriyum.domain.store.closure.repository.*;
import com.miriyum.domain.store.entity.Store;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.VerificationStatus;
import com.miriyum.domain.store.repository.StoreRepository;
import com.miriyum.domain.store.schedule.dto.*;
import com.miriyum.domain.store.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.store.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.store.schedule.entity.StoreScheduleState;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
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

    @Test void staleReservationOperatingLinkFailsClosed() {
        StoreServiceIntervalValidationService service = new StoreServiceIntervalValidationService(
                stores, states, operating, reservation, regular, temporary);
        Store store = mock(Store.class);
        StoreScheduleState state = mock(StoreScheduleState.class);
        OperatingScheduleVersion op = mock(OperatingScheduleVersion.class);
        ReservationScheduleVersion res = mock(ReservationScheduleVersion.class);
        com.miriyum.domain.store.closure.entity.RegularClosureVersion reg =
                mock(com.miriyum.domain.store.closure.entity.RegularClosureVersion.class);
        given(store.getId()).willReturn(1L);
        given(store.getVerificationStatus()).willReturn(VerificationStatus.APPROVED);
        given(state.getStoreId()).willReturn(1L);
        given(state.getActiveOperatingScheduleVersionId()).willReturn(10L);
        given(state.getActiveReservationScheduleVersionId()).willReturn(20L);
        given(state.getActiveRegularClosureVersionId()).willReturn(30L);
        given(op.getId()).willReturn(10L); given(op.getStoreId()).willReturn(1L);
        given(res.getId()).willReturn(20L); given(res.getStoreId()).willReturn(1L);
        given(res.getValidatedOperatingVersionId()).willReturn(9L);
        given(reg.getId()).willReturn(30L); given(reg.getStoreId()).willReturn(1L);
        given(stores.findAllById(org.mockito.ArgumentMatchers.any())).willReturn(List.of(store));
        given(states.findAllByStoreIdIn(org.mockito.ArgumentMatchers.any())).willReturn(List.of(state));
        given(operating.findActiveByIdsWithEntries(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(ScheduleVersionStatus.ACTIVE))).willReturn(List.of(op));
        given(reservation.findActiveByIdsWithEntries(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(ScheduleVersionStatus.ACTIVE))).willReturn(List.of(res));
        given(regular.findActiveByIdsWithEntries(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(ScheduleVersionStatus.ACTIVE))).willReturn(List.of(reg));
        given(temporary.findOverlapping(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).willReturn(List.of());

        assertThat(service.validateServiceIntervals(List.of(request(1))).getFirst().status())
                .isEqualTo(StoreServiceIntervalStatus.NOT_ACCEPTING);
        then(stores).should().findAllById(org.mockito.ArgumentMatchers.any());
        then(states).should().findAllByStoreIdIn(org.mockito.ArgumentMatchers.any());
        then(operating).should().findActiveByIdsWithEntries(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(ScheduleVersionStatus.ACTIVE));
        then(reservation).should().findActiveByIdsWithEntries(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(ScheduleVersionStatus.ACTIVE));
        then(regular).should().findActiveByIdsWithEntries(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(ScheduleVersionStatus.ACTIVE));
        then(temporary).should().findOverlapping(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private StoreServiceIntervalRequest request(long storeId) {
        return new StoreServiceIntervalRequest(storeId, Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"));
    }
}
