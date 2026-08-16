package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreWaitingImpactQueryServiceTest {

    @Mock
    private WaitingTeamRepository waitingTeams;

    @InjectMocks
    private StoreWaitingImpactQueryService service;

    @Test
    void inspectCountsOnlyActiveWaitingStatesForTargetStore() {
        Set<WaitingTeamStatus> active = Set.of(
                WaitingTeamStatus.WAITING,
                WaitingTeamStatus.CALLED,
                WaitingTeamStatus.ARRIVED);
        given(waitingTeams.findIdsByStoreIdAndStatusIn(7L, active)).willReturn(List.of(11L, 12L, 13L));

        var impact = service.inspect(7L);

        assertThat(impact.storeId()).isEqualTo(7L);
        assertThat(impact.activeTeamCount()).isEqualTo(3L);
        assertThat(impact.waitingTeamIds()).containsExactlyInAnyOrder(11L, 12L, 13L);
    }
}
