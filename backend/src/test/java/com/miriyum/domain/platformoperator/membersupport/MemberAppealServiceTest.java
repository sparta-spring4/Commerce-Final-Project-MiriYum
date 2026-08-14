package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.membersupport.MemberAccountSupportPort;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.auth.membersupport.RestrictedFeature;
import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberAppealOutcome;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanctionStatus;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportAuditRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
import com.miriyum.domain.platformoperator.service.HighRiskCommandGuard;
import com.miriyum.domain.platformoperator.service.membersupport.MemberAppealService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportAuditWriter;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class MemberAppealServiceTest {
    @Test
    void reductionAppendsFeatureRestrictionRevisionAndClearsSuspension() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
        MemberSupportCase enforcement = MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, 41, 3, "ABUSE", LocalDateTime.now(clock).minusDays(2));
        MemberSanction original = MemberSanction.propose(
                enforcement, MemberSanctionLevel.TEMPORARY_SUSPENSION, Set.of(),
                "ABUSE", "v1", 9, LocalDateTime.now(clock).minusDays(2));
        ReflectionTestUtils.setField(original, "id", 5L);
        MemberSupportCase appeal = MemberSupportCase.appeal(
                MemberAccountType.CONSUMER, 41, 5, 4, LocalDateTime.now(clock));
        appeal.assign();
        MemberSupportCaseRepository cases = mock(MemberSupportCaseRepository.class);
        when(cases.findByPublicIdForUpdate(appeal.getPublicId())).thenReturn(Optional.of(appeal));
        MemberSanctionRepository sanctions = mock(MemberSanctionRepository.class);
        when(sanctions.findByIdForUpdate(5L)).thenReturn(Optional.of(original));
        when(sanctions.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        MemberAccountSupportPort port = mock(MemberAccountSupportPort.class);
        when(port.accountType()).thenReturn(MemberAccountType.CONSUMER);
        when(port.clearSuspension(41, 4)).thenReturn(5L);
        HighRiskCommandGuard guard = mock(HighRiskCommandGuard.class);
        when(guard.authorize(org.mockito.ArgumentMatchers.any())).thenReturn(new AdminAuditContext(
                10, Set.of(), Set.of(PlatformOperatorPermission.ACCOUNT_APPEAL_REVIEW), 2,
                AdminCaseType.MEMBER_SUPPORT, appeal.getPublicId(), 2,
                AdminCommandPurpose.ACCOUNT_APPEAL_DECISION, AdminTargetType.CONSUMER_ACCOUNT,
                "41", "fingerprint", "correlation"));
        MemberAppealService service = new MemberAppealService(
                cases, sanctions, new MemberAccountSupportRegistry(List.of(port)), guard,
                mock(AdminCaseAssignmentManager.class),
                new MemberSupportAuditWriter(mock(MemberSupportAuditRepository.class), clock), clock);
        PlatformOperatorPrincipal principal = new PlatformOperatorPrincipal(
                10L, "admin@example.com", "session", 2, 3, false);

        service.decide(principal, appeal.getPublicId(), 2, 4, MemberAppealOutcome.REDUCE,
                MemberSanctionLevel.FEATURE_RESTRICTION, Set.of(RestrictedFeature.RESERVATION),
                "approval", "correlation", "MITIGATED");

        assertThat(original.getStatus()).isEqualTo(MemberSanctionStatus.REDUCED);
        assertThat(appeal.getStatus()).isEqualTo(MemberSupportCaseStatus.REDUCED);
        ArgumentCaptor<MemberSanction> revision = ArgumentCaptor.forClass(MemberSanction.class);
        verify(sanctions).save(revision.capture());
        assertThat(revision.getValue().getLevel()).isEqualTo(MemberSanctionLevel.FEATURE_RESTRICTION);
        assertThat(revision.getValue().getEndsAt()).isEqualTo(LocalDateTime.now(clock).plusDays(7));
        verify(port).clearSuspension(41, 4);
    }
}
