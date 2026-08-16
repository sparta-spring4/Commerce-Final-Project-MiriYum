package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.membersupport.MemberAccountSnapshot;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportPort;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberVerificationChannel;
import com.miriyum.domain.platformoperator.dto.membersupport.PublicMemberSupportRequests.ConsumedVerification;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberVerificationPurpose;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseSubmissionStore;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanction;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanctionStatus;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportSubmissionService;
import com.miriyum.domain.platformoperator.service.membersupport.MockMemberIdentityVerificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemberSupportSubmissionServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void validProofCreatesRecoveryCaseWithoutContactData() {
        MockMemberIdentityVerificationService verifications = mock(MockMemberIdentityVerificationService.class);
        MemberSupportCaseSubmissionStore cases = mock(MemberSupportCaseSubmissionStore.class);
        MemberAccountSupportPort port = mock(MemberAccountSupportPort.class);
        when(port.accountType()).thenReturn(MemberAccountType.CONSUMER);
        when(port.findMinimal(41)).thenReturn(Optional.of(new MemberAccountSnapshot(
                MemberAccountType.CONSUMER, 41, false, false,
                Instant.parse("2026-08-01T00:00:00Z"), 3)));
        when(verifications.consumeForSubmission(
                "proof", MemberAccountType.CONSUMER, MemberVerificationPurpose.MEMBER_RECOVERY))
                .thenReturn(Optional.of(new ConsumedVerification(7, 41, "new@example.com")));
        MemberSupportSubmissionService service = new MemberSupportSubmissionService(
                verifications, new MemberAccountSupportRegistry(List.of(port)), cases,
                mock(MemberSanctionRepository.class), CLOCK);

        service.submitRecovery(MemberAccountType.CONSUMER, "proof", "new@example.com");

        ArgumentCaptor<MemberSupportCase> saved = ArgumentCaptor.forClass(MemberSupportCase.class);
        verify(cases).insertIfAbsent(saved.capture());
        assertThat(saved.getValue().getAccountId()).isEqualTo(41);
        assertThat(saved.getValue().getTargetSupportVersion()).isEqualTo(3);
        assertThat(saved.getValue().toString()).doesNotContain("new@example.com", "proof");
    }

    @Test
    void invalidOrMismatchedProofStillReturnsNormallyWithoutCreatingCase() {
        MockMemberIdentityVerificationService verifications = mock(MockMemberIdentityVerificationService.class);
        MemberSupportCaseSubmissionStore cases = mock(MemberSupportCaseSubmissionStore.class);
        MemberAccountSupportRegistry accounts = mock(MemberAccountSupportRegistry.class);
        when(verifications.consumeForSubmission(
                "bad", MemberAccountType.CONSUMER, MemberVerificationPurpose.MEMBER_RECOVERY))
                .thenReturn(Optional.empty());
        MemberSupportSubmissionService service = new MemberSupportSubmissionService(
                verifications, accounts, cases, mock(MemberSanctionRepository.class), CLOCK);

        service.submitRecovery(MemberAccountType.CONSUMER, "bad", "new@example.com");

        verify(cases, never()).insertIfAbsent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void appealSubmissionStoresNoContactOrStatement() {
        MemberSupportCaseSubmissionStore cases = mock(MemberSupportCaseSubmissionStore.class);
        MemberSanctionRepository sanctions = mock(MemberSanctionRepository.class);
        MemberSanction sanction = mock(MemberSanction.class);
        when(sanction.getAccountType()).thenReturn(MemberAccountType.CONSUMER);
        when(sanction.getAccountId()).thenReturn(41L);
        when(sanction.getId()).thenReturn(5L);
        when(sanction.getStatus()).thenReturn(MemberSanctionStatus.APPLIED);
        when(sanctions.findByPublicId("sanction-id")).thenReturn(Optional.of(sanction));
        MemberAccountSupportPort port = mock(MemberAccountSupportPort.class);
        when(port.accountType()).thenReturn(MemberAccountType.CONSUMER);
        when(port.findMinimal(41)).thenReturn(Optional.of(new MemberAccountSnapshot(
                MemberAccountType.CONSUMER, 41, false, true, Instant.now(), 4)));
        MockMemberIdentityVerificationService verifier = mock(MockMemberIdentityVerificationService.class);
        when(verifier.verifyAppeal(MemberAccountType.CONSUMER, 41, 5,
                MemberVerificationChannel.REGISTERED_EMAIL, "private contact")).thenReturn(Optional.of(17L));
        MemberSupportSubmissionService service = new MemberSupportSubmissionService(
                verifier,
                new MemberAccountSupportRegistry(List.of(port)), cases, sanctions, CLOCK);

        service.submitAppeal(MemberAccountType.CONSUMER, "sanction-id",
                MemberVerificationChannel.REGISTERED_EMAIL, "private contact", "private statement");

        ArgumentCaptor<MemberSupportCase> saved = ArgumentCaptor.forClass(MemberSupportCase.class);
        verify(cases).insertIfAbsent(saved.capture());
        assertThat(saved.getValue().toString()).doesNotContain("private contact", "private statement");
        assertThat(saved.getValue().getIdentityVerificationId()).isEqualTo(17L);
    }
}
