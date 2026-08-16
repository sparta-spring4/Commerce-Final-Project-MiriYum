package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.membersupport.MemberAccountSupportPort;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.platformoperator.dto.authorization.AdminAuditContext;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberIdentityVerification;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportAudit;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.domain.platformoperator.enums.AdminCommandPurpose;
import com.miriyum.domain.platformoperator.enums.AdminTargetType;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPermission;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberIdentityVerificationRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportAuditRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import com.miriyum.domain.platformoperator.service.AdminCaseAssignmentManager;
import com.miriyum.domain.platformoperator.service.HighRiskCommandGuard;
import com.miriyum.domain.platformoperator.service.membersupport.MemberRecoveryService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportAuditWriter;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportCrypto;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class MemberRecoveryServiceTest {
    @Test
    void approvalChangesAccountClosesAssignmentAndWritesOnlyRedactedAudit() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
        MemberSupportCrypto crypto = new MemberSupportCrypto(
                "proof", Base64.getEncoder().encodeToString(new byte[32]));
        MemberIdentityVerification verification = MemberIdentityVerification.recovery(
                crypto.digest("proof"), MemberAccountType.CONSUMER, 41,
                crypto.encrypt("new@example.com"), crypto.digest("new@example.com"),
                LocalDateTime.now(clock).minusMinutes(1), LocalDateTime.now(clock).plusMinutes(14));
        ReflectionTestUtils.setField(verification, "id", 7L);
        MemberSupportCase supportCase = MemberSupportCase.recovery(
                MemberAccountType.CONSUMER, 41, 7, 3, LocalDateTime.now(clock));
        supportCase.assign();
        MemberSupportCaseRepository cases = mock(MemberSupportCaseRepository.class);
        when(cases.findByPublicIdForUpdate(supportCase.getPublicId())).thenReturn(Optional.of(supportCase));
        MemberIdentityVerificationRepository verifications = mock(MemberIdentityVerificationRepository.class);
        when(verifications.findById(7L)).thenReturn(Optional.of(verification));
        MemberAccountSupportPort port = mock(MemberAccountSupportPort.class);
        when(port.accountType()).thenReturn(MemberAccountType.CONSUMER);
        when(port.approveRecovery(41, 3, "new@example.com")).thenReturn(4L);
        HighRiskCommandGuard guard = mock(HighRiskCommandGuard.class);
        PlatformOperatorPrincipal principal = new PlatformOperatorPrincipal(
                9L, "admin@example.com", "session", 2, 3, false);
        when(guard.authorize(org.mockito.ArgumentMatchers.any())).thenReturn(new AdminAuditContext(
                9, Set.of(), Set.of(PlatformOperatorPermission.MEMBER_RECOVERY), 2,
                AdminCaseType.MEMBER_SUPPORT, supportCase.getPublicId(), 2,
                AdminCommandPurpose.MEMBER_RECOVERY, AdminTargetType.CONSUMER_ACCOUNT, "41",
                "fingerprint", "correlation"));
        AdminCaseAssignmentManager assignments = mock(AdminCaseAssignmentManager.class);
        MemberSupportAuditRepository audits = mock(MemberSupportAuditRepository.class);
        MemberRecoveryService service = new MemberRecoveryService(
                cases, verifications, crypto, new MemberAccountSupportRegistry(List.of(port)), guard,
                assignments, new MemberSupportAuditWriter(audits, clock), clock);

        service.decide(principal, supportCase.getPublicId(), 2, "approval-secret",
                "correlation", true, "IDENTITY_MATCHED");

        assertThat(supportCase.getStatus()).isEqualTo(MemberSupportCaseStatus.APPROVED);
        verify(port).approveRecovery(41, 3, "new@example.com");
        verify(assignments).close(org.mockito.ArgumentMatchers.any());
        ArgumentCaptor<MemberSupportAudit> audit = ArgumentCaptor.forClass(MemberSupportAudit.class);
        verify(audits).save(audit.capture());
        assertThat(audit.getValue().toString())
                .doesNotContain("approval-secret", "new@example.com", "old@example.com");
        assertThat(audit.getValue().getRetentionUntil()).isEqualTo(LocalDateTime.now(clock).plusYears(3));
    }
}
