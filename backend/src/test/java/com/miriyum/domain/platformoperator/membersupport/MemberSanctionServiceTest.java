package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportPort;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.auth.membersupport.RestrictedFeature;
import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanctionApproval;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanctionStatus;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorRole;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionApprovalRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportAuditRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
import com.miriyum.domain.platformoperator.service.HighRiskCommandGuard;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSanctionService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportAuditWriter;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
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

class MemberSanctionServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
    private static final PlatformOperatorPrincipal PROPOSER =
            new PlatformOperatorPrincipal(9L, "admin@example.com", "s1", 2, 3, false);

    @Test
    void temporarySuspensionIsExactlyThirtyDaysAndSuspendsAccount() {
        Fixture fixture = fixture(MemberSanctionLevel.TEMPORARY_SUSPENSION);

        MemberSanction result = fixture.service.apply(
                PROPOSER, fixture.supportCase.getPublicId(), 2, 3,
                MemberSanctionLevel.TEMPORARY_SUSPENSION, Set.of(), "ABUSE", "v1",
                "approval", "correlation");

        assertThat(result.getStatus()).isEqualTo(MemberSanctionStatus.APPLIED);
        assertThat(result.getEndsAt()).isEqualTo(LocalDateTime.now(CLOCK).plusDays(30));
        verify(fixture.port).applySuspension(41, 3);
    }

    @Test
    void featureRestrictionAdvancesTheSameAccountSupportVersionCas() {
        Fixture fixture = fixture(MemberSanctionLevel.FEATURE_RESTRICTION);

        fixture.service.apply(PROPOSER, fixture.supportCase.getPublicId(), 2, 3,
                MemberSanctionLevel.FEATURE_RESTRICTION, Set.of(RestrictedFeature.RESERVATION),
                "ABUSE", "v1", "approval", "correlation");

        verify(fixture.port).advanceSupportVersion(41, 3);
        verify(fixture.port, never()).applySuspension(41, 3);
    }

    @Test
    void permanentSuspensionNeedsRecordedApprovalFromDifferentSuperAdmin() {
        Fixture fixture = fixture(MemberSanctionLevel.PERMANENT_SUSPENSION);
        MemberSanction pending = fixture.service.apply(
                PROPOSER, fixture.supportCase.getPublicId(), 2, 3,
                MemberSanctionLevel.PERMANENT_SUSPENSION, Set.of(), "SEVERE_ABUSE", "v1",
                "approval", "proposal-correlation");
        ReflectionTestUtils.setField(pending, "id", 5L);
        when(fixture.sanctions.findByPublicIdForUpdate(pending.getPublicId())).thenReturn(Optional.of(pending));

        assertThat(pending.getStatus()).isEqualTo(MemberSanctionStatus.PENDING_ADDITIONAL_APPROVAL);
        verify(fixture.port, never()).applySuspension(41, 3);
        assertThatThrownBy(() -> fixture.service.approvePermanent(
                PROPOSER, pending.getPublicId(), fixture.supportCase.getRowVersion(), 3,
                "approval-2", "self-correlation", "CONFIRMED"))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.PERMANENT_SANCTION_APPROVAL_CONFLICT));

        PlatformOperatorPrincipal approver = new PlatformOperatorPrincipal(
                10L, "super@example.com", "s2", 4, 5, false);
        when(fixture.guard.authorize(org.mockito.ArgumentMatchers.any())).thenReturn(context(
                10, fixture.supportCase, AdminCommandPurpose.PERMANENT_ACCOUNT_SANCTION_APPROVAL,
                Set.of(PlatformOperatorRole.SUPER_ADMIN), "approve-correlation"));
        fixture.service.approvePermanent(
                approver, pending.getPublicId(), fixture.supportCase.getRowVersion(), 3,
                "approval-2", "approve-correlation", "CONFIRMED");

        verify(fixture.port).applySuspension(41, 3);
        ArgumentCaptor<MemberSanctionApproval> recorded = ArgumentCaptor.forClass(MemberSanctionApproval.class);
        verify(fixture.approvals).save(recorded.capture());
        assertThat(recorded.getValue().getProposerOperatorId()).isEqualTo(9);
        assertThat(recorded.getValue().getApproverOperatorId()).isEqualTo(10);
    }

    private Fixture fixture(MemberSanctionLevel level) {
        MemberSupportCase supportCase = MemberSupportCase.enforcement(
                MemberAccountType.CONSUMER, 41, 3, "ABUSE", LocalDateTime.now(CLOCK));
        supportCase.assign();
        MemberSupportCaseRepository cases = mock(MemberSupportCaseRepository.class);
        when(cases.findByPublicIdForUpdate(supportCase.getPublicId())).thenReturn(Optional.of(supportCase));
        MemberSanctionRepository sanctions = mock(MemberSanctionRepository.class);
        when(sanctions.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        MemberSanctionApprovalRepository approvals = mock(MemberSanctionApprovalRepository.class);
        MemberAccountSupportPort port = mock(MemberAccountSupportPort.class);
        when(port.accountType()).thenReturn(MemberAccountType.CONSUMER);
        when(port.applySuspension(41, 3)).thenReturn(4L);
        HighRiskCommandGuard guard = mock(HighRiskCommandGuard.class);
        when(guard.authorize(org.mockito.ArgumentMatchers.any())).thenReturn(context(
                9, supportCase, AdminCommandPurpose.ACCOUNT_SANCTION, Set.of(), "correlation"));
        MemberSanctionService service = new MemberSanctionService(
                cases, sanctions, approvals, new MemberAccountSupportRegistry(List.of(port)), guard,
                mock(AdminCaseAssignmentManager.class),
                new MemberSupportAuditWriter(mock(MemberSupportAuditRepository.class), CLOCK), CLOCK);
        return new Fixture(service, supportCase, sanctions, approvals, port, guard);
    }

    private AdminAuditContext context(long operatorId, MemberSupportCase supportCase,
                                      AdminCommandPurpose purpose, Set<PlatformOperatorRole> roles,
                                      String correlation) {
        return new AdminAuditContext(operatorId, roles,
                Set.of(PlatformOperatorPermission.ACCOUNT_SANCTION,
                        PlatformOperatorPermission.ACCOUNT_PERMANENT_SANCTION_APPROVE),
                2, AdminCaseType.MEMBER_SUPPORT, supportCase.getPublicId(), supportCase.getRowVersion(),
                purpose, AdminTargetType.CONSUMER_ACCOUNT, "41", "fingerprint", correlation);
    }

    private record Fixture(MemberSanctionService service, MemberSupportCase supportCase,
                           MemberSanctionRepository sanctions, MemberSanctionApprovalRepository approvals,
                           MemberAccountSupportPort port, HighRiskCommandGuard guard) {
    }
}
