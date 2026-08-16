package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.reservation.waiting.dto.WaitingAnalyticsSnapshot;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WaitingAnalyticsQueryServiceTest {

    private static final long STORE_ID = 17L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 16);
    private static final Instant AS_OF = Instant.parse("2026-08-16T09:00:00Z");

    @Mock WaitingTeamRepository teamRepository;
    @Mock WaitingStatusEventRepository eventRepository;
    @Mock WaitingStatusEventRepository.WaitingDashboardAggregate aggregate;
    @Mock WaitingTeamRepository.WaitingDashboardCheckpoint checkpoint;

    private WaitingAnalyticsQueryService service;

    @BeforeEach
    void setUp() {
        service = new WaitingAnalyticsQueryService(teamRepository, eventRepository);
    }

    @Test
    void countsOnlyWaitingAndCalledAsCurrentQueueAndKeepsNoShowSeparate() {
        given(eventRepository.aggregateDashboardState(STORE_ID, BUSINESS_DATE, AS_OF))
                .willReturn(aggregate);
        given(teamRepository.dashboardCheckpoint(STORE_ID, BUSINESS_DATE, AS_OF))
                .willReturn(checkpoint);
        given(aggregate.getWaitingTeams()).willReturn(3L);
        given(aggregate.getCalledTeams()).willReturn(1L);
        given(aggregate.getWaitingPeople()).willReturn(8L);
        given(aggregate.getCalledPeople()).willReturn(2L);
        given(aggregate.getLongestWaitSeconds()).willReturn(1260L);
        given(aggregate.getConfirmedNoShowTeams()).willReturn(2L);
        given(aggregate.getMaxEventId()).willReturn(91L);
        given(aggregate.getDataThroughEpochMicros()).willReturn(epochMicros(AS_OF.minusSeconds(5)));
        given(checkpoint.getMaxTeamId()).willReturn(71L);
        given(aggregate.getMaxEventSequence()).willReturn(8L);

        WaitingAnalyticsSnapshot result = service.getDashboardSnapshot(
                STORE_ID, BUSINESS_DATE, AS_OF);

        assertThat(result.waitingTeams()).isEqualTo(3);
        assertThat(result.calledTeams()).isEqualTo(1);
        assertThat(result.waitingPeople()).isEqualTo(8);
        assertThat(result.calledPeople()).isEqualTo(2);
        assertThat(result.longestWaitSeconds()).isEqualTo(1260L);
        assertThat(result.confirmedNoShowTeams()).isEqualTo(2);
        assertThat(result.inputCheckpoint()).hasSize(64);
        assertThat(result.dataThrough()).isEqualTo(AS_OF.minusSeconds(5));
        assertThat(result.sourceVersion()).isEqualTo(91L);
    }

    private static long epochMicros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }
}
