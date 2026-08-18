package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingReceptionAvailability;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.domain.store.service.StoreAdministrationService;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WaitingConsumerQueryServiceTest {

    @Test
    void availabilityUsesTheInjectedCentralClockAfterRevalidatingTheAccount() {
        ConsumerAccountService accounts = mock(ConsumerAccountService.class);
        WaitingActiveMembershipRepository memberships = mock(WaitingActiveMembershipRepository.class);
        WaitingTeamRepository teams = mock(WaitingTeamRepository.class);
        WaitingReceptionGate receptionGate = mock(WaitingReceptionGate.class);
        StoreAdministrationService storeAdministration = mock(StoreAdministrationService.class);
        Instant now = Instant.parse("2026-08-17T00:00:00Z");
        WaitingReceptionAvailability expected = WaitingReceptionAvailability.open(
                100L, LocalDate.of(2026, 8, 17));
        when(receptionGate.inspect(100L, now)).thenReturn(expected);

        WaitingReceptionAvailability result = new WaitingConsumerQueryService(
                accounts, memberships, teams, receptionGate, storeAdministration,
                Clock.fixed(now, ZoneOffset.UTC))
                .getAvailability(200L, 100L);

        assertThat(result).isEqualTo(expected);
        verify(accounts).requireActiveAccount(200L);
        verify(storeAdministration).requireStoreExists(100L);
        verify(receptionGate).inspect(100L, now);
    }

    @Test
    void currentStartsFromTheAccountsActiveMembershipAndCountsOnlyActiveTeamsAhead() {
        ConsumerAccountService accounts = mock(ConsumerAccountService.class);
        WaitingActiveMembershipRepository memberships = mock(WaitingActiveMembershipRepository.class);
        WaitingTeamRepository teams = mock(WaitingTeamRepository.class);
        WaitingReceptionGate receptionGate = mock(WaitingReceptionGate.class);
        StoreAdministrationService storeAdministration = mock(StoreAdministrationService.class);
        WaitingActiveMembership membership = mock(WaitingActiveMembership.class);
        WaitingTeam team = waitingTeam(300L, 100L, 200L, 9L);
        when(membership.getWaitingTeamId()).thenReturn(300L);
        when(memberships.findByConsumerAccountId(200L)).thenReturn(Optional.of(membership));
        when(teams.findById(300L)).thenReturn(Optional.of(team));
        when(teams.countActiveAhead(100L, LocalDate.of(2026, 8, 17), 9L)).thenReturn(3L);

        WaitingConsumerSnapshot result = new WaitingConsumerQueryService(
                accounts, memberships, teams, receptionGate, storeAdministration,
                Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC))
                .getCurrent(200L);

        assertThat(result.waitingTeamId()).isEqualTo("300");
        assertThat(result.storeId()).isEqualTo("100");
        assertThat(result.teamsAhead()).isEqualTo(3L);
        assertThat(result.arrivalDeadline()).isEqualTo(Instant.parse("2026-08-17T00:10:00Z"));
        verify(accounts).requireActiveAccount(200L);
    }

    @Test
    void currentHidesWhetherAnotherConsumersTeamExists() {
        ConsumerAccountService accounts = mock(ConsumerAccountService.class);
        WaitingActiveMembershipRepository memberships = mock(WaitingActiveMembershipRepository.class);
        WaitingTeamRepository teams = mock(WaitingTeamRepository.class);
        WaitingReceptionGate receptionGate = mock(WaitingReceptionGate.class);
        StoreAdministrationService storeAdministration = mock(StoreAdministrationService.class);
        WaitingActiveMembership membership = mock(WaitingActiveMembership.class);
        WaitingTeam other = waitingTeam(300L, 100L, 999L, 9L);
        when(membership.getWaitingTeamId()).thenReturn(300L);
        when(memberships.findByConsumerAccountId(200L)).thenReturn(Optional.of(membership));
        when(teams.findById(300L)).thenReturn(Optional.of(other));

        WaitingConsumerQueryService service = new WaitingConsumerQueryService(
                accounts, memberships, teams, receptionGate, storeAdministration,
                Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.getCurrent(200L))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_TEAM_NOT_FOUND));
    }

    private static WaitingTeam waitingTeam(
            long teamId,
            long storeId,
            long consumerAccountId,
            long queueSequence
    ) {
        WaitingTeam team = mock(WaitingTeam.class);
        when(team.getId()).thenReturn(teamId);
        when(team.getStoreId()).thenReturn(storeId);
        when(team.getConsumerAccountId()).thenReturn(consumerAccountId);
        when(team.getBusinessDate()).thenReturn(LocalDate.of(2026, 8, 17));
        when(team.getStatus()).thenReturn(WaitingTeamStatus.CALLED);
        when(team.getQueueSequence()).thenReturn(queueSequence);
        when(team.getPartySize()).thenReturn(2);
        when(team.getCreatedAt()).thenReturn(Instant.parse("2026-08-17T00:00:00Z"));
        when(team.getCalledAt()).thenReturn(Instant.parse("2026-08-17T00:00:00Z"));
        when(team.getArrivalDeadline()).thenReturn(Instant.parse("2026-08-17T00:10:00Z"));
        when(team.getVersion()).thenReturn(1L);
        return team;
    }
}
