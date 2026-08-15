package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.membersupport.MemberAccountSnapshot;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportPort;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanctionStatus;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSanctionExpiryService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MemberSanctionExpiryServiceTest {
    @Test
    void lastTemporarySuspensionExpiryReactivatesAccountOnce() {
        Clock proposedClock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC);
        MemberSupportCase supportCase = MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, 41, 3, "ABUSE", LocalDateTime.now(proposedClock));
        MemberSanction sanction = MemberSanction.propose(
                supportCase, MemberSanctionLevel.TEMPORARY_SUSPENSION, Set.of(),
                "ABUSE", "v1", 9, LocalDateTime.now(proposedClock));
        ReflectionTestUtils.setField(sanction, "id", 5L);
        Clock expiryClock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
        MemberSanctionRepository sanctions = mock(MemberSanctionRepository.class);
        when(sanctions.findExpiredForUpdate(LocalDateTime.now(expiryClock))).thenReturn(List.of(sanction));
        when(sanctions.existsOtherActiveSuspension(
                MemberAccountType.CONSUMER, 41, 5, LocalDateTime.now(expiryClock))).thenReturn(false);
        MemberAccountSupportPort port = mock(MemberAccountSupportPort.class);
        when(port.accountType()).thenReturn(MemberAccountType.CONSUMER);
        when(port.findMinimal(41)).thenReturn(Optional.of(new MemberAccountSnapshot(
                MemberAccountType.CONSUMER, 41, false, true, Instant.now(), 4)));
        MemberSanctionExpiryService service = new MemberSanctionExpiryService(
                sanctions, new MemberAccountSupportRegistry(List.of(port)), expiryClock);

        service.expireDueSanctions();

        assertThat(sanction.getStatus()).isEqualTo(MemberSanctionStatus.EXPIRED);
        verify(port).clearSuspension(41, 4);
    }

    @Test
    void featureRestrictionExpiryAlsoAdvancesAccountCasVersion() {
        Clock proposedClock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC);
        MemberSupportCase supportCase = MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, 41, 3, "ABUSE", LocalDateTime.now(proposedClock));
        MemberSanction sanction = MemberSanction.propose(
                supportCase, MemberSanctionLevel.FEATURE_RESTRICTION,
                Set.of(com.miriyum.domain.auth.membersupport.RestrictedFeature.RESERVATION),
                "ABUSE", "v1", 9, LocalDateTime.now(proposedClock));
        Clock expiryClock = Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC);
        MemberSanctionRepository sanctions = mock(MemberSanctionRepository.class);
        when(sanctions.findExpiredForUpdate(LocalDateTime.now(expiryClock))).thenReturn(List.of(sanction));
        MemberAccountSupportPort port = mock(MemberAccountSupportPort.class);
        when(port.accountType()).thenReturn(MemberAccountType.CONSUMER);
        when(port.findMinimal(41)).thenReturn(Optional.of(new MemberAccountSnapshot(
                MemberAccountType.CONSUMER, 41, false, false, Instant.now(), 4)));
        MemberSanctionExpiryService service = new MemberSanctionExpiryService(
                sanctions, new MemberAccountSupportRegistry(List.of(port)), expiryClock);

        service.expireDueSanctions();

        verify(port).advanceSupportVersion(41, 4);
    }
}
