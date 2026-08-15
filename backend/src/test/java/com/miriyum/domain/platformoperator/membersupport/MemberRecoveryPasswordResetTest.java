package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.membersupport.MemberAccountSupportPort;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberIdentityVerification;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberIdentityVerificationRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import com.miriyum.domain.platformoperator.service.membersupport.MemberPasswordResetCredentialService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberRecoveryService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportCrypto;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MemberRecoveryPasswordResetTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void consumedRecoveryVerificationCannotBeReusedAsPasswordResetCredential() {
        MemberSupportCrypto crypto = crypto();
        MemberIdentityVerification verification = MemberIdentityVerification.recovery(
                crypto.digest("raw-proof"), MemberAccountType.CONSUMER, 41,
                crypto.encrypt("new@example.com"), crypto.digest("new@example.com"),
                LocalDateTime.now(CLOCK).minusMinutes(1), LocalDateTime.now(CLOCK).plusMinutes(14));
        ReflectionTestUtils.setField(verification, "id", 7L);
        MemberSupportCase supportCase = approvedCase();
        MemberIdentityVerificationRepository verifications = mock(MemberIdentityVerificationRepository.class);
        when(verifications.findByProofDigest(crypto.digest("raw-proof"))).thenReturn(Optional.of(verification));
        MemberSupportCaseRepository cases = mock(MemberSupportCaseRepository.class);
        when(cases.findByIdentityVerificationIdAndStatus(7L, MemberSupportCaseStatus.APPROVED))
                .thenReturn(Optional.of(supportCase));
        MemberRecoveryService service = new MemberRecoveryService(
                cases, verifications, crypto, new MemberAccountSupportRegistry(List.of(port())),
                null, null, null, CLOCK);

        assertThatThrownBy(() -> service.completePasswordReset(
                "raw-proof", MemberAccountType.CONSUMER, "NewPassword1!"))
                .isInstanceOf(com.miriyum.global.exception.ServiceException.class);
    }

    @Test
    void approvalCanExchangeAnExpiredTrackingVerificationForOneCurrentResetCredential() {
        MemberSupportCrypto crypto = crypto();
        MemberIdentityVerification tracking = MemberIdentityVerification.recovery(
                crypto.digest("tracking-proof"), MemberAccountType.CONSUMER, 41,
                crypto.encrypt("new@example.com"), crypto.digest("new@example.com"),
                LocalDateTime.now(CLOCK).minusHours(2), LocalDateTime.now(CLOCK).minusHours(1));
        ReflectionTestUtils.setField(tracking, "id", 7L);
        ReflectionTestUtils.setField(tracking, "consumedAt", LocalDateTime.now(CLOCK).minusHours(1));
        MemberSupportCase supportCase = approvedCase();
        MemberIdentityVerificationRepository verifications = mock(MemberIdentityVerificationRepository.class);
        when(verifications.findByProofDigestForUpdate(crypto.digest("tracking-proof")))
                .thenReturn(Optional.of(tracking));
        doAnswer(invocation -> {
            MemberIdentityVerification saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 8L);
            return saved;
        }).when(verifications).save(any(MemberIdentityVerification.class));
        MemberSupportCaseRepository cases = mock(MemberSupportCaseRepository.class);
        when(cases.findByIdentityVerificationIdForUpdate(7L)).thenReturn(Optional.of(supportCase));
        MemberPasswordResetCredentialService service = new MemberPasswordResetCredentialService(
                cases, verifications, crypto, properties(), CLOCK);

        var issued = service.exchange(MemberAccountType.CONSUMER, "tracking-proof");

        assertThat(issued).isPresent();
        MemberIdentityVerification reset = org.mockito.Mockito.mockingDetails(verifications)
                .getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("save"))
                .map(invocation -> (MemberIdentityVerification) invocation.getArgument(0))
                .findFirst().orElseThrow();
        assertThat(reset.getPurpose().name()).isEqualTo("PASSWORD_RESET");
        assertThat(reset.getExpiresAt()).isEqualTo(LocalDateTime.now(CLOCK).plusMinutes(15));
        assertThat(service.exchange(MemberAccountType.CONSUMER, "tracking-proof")).isEmpty();
    }

    @Test
    void currentResetCredentialIsConsumedOnceAndCompletesTheApprovedCase() {
        MemberSupportCrypto crypto = crypto();
        MemberIdentityVerification reset = MemberIdentityVerification.passwordReset(
                crypto.digest("reset-proof"), MemberAccountType.CONSUMER, 41,
                LocalDateTime.now(CLOCK).minusMinutes(1), LocalDateTime.now(CLOCK).plusMinutes(14));
        ReflectionTestUtils.setField(reset, "id", 8L);
        MemberSupportCase supportCase = approvedCase();
        supportCase.issuePasswordResetVerification(8L);
        MemberIdentityVerificationRepository verifications = mock(MemberIdentityVerificationRepository.class);
        when(verifications.findByProofDigestForUpdate(crypto.digest("reset-proof")))
                .thenReturn(Optional.of(reset));
        MemberSupportCaseRepository cases = mock(MemberSupportCaseRepository.class);
        when(cases.findByPasswordResetVerificationIdForUpdate(8L)).thenReturn(Optional.of(supportCase));
        MemberAccountSupportPort port = port();
        MemberRecoveryService service = new MemberRecoveryService(
                cases, verifications, crypto, new MemberAccountSupportRegistry(List.of(port)),
                null, null, null, CLOCK);

        service.completePasswordReset("reset-proof", MemberAccountType.CONSUMER, "NewPassword1!");

        verify(port).replaceRecoveredPassword(41, "NewPassword1!");
        assertThat(supportCase.getPasswordResetCompletedAt()).isEqualTo(LocalDateTime.now(CLOCK));
        assertThatThrownBy(() -> service.completePasswordReset(
                "reset-proof", MemberAccountType.CONSUMER, "NewPassword2@"))
                .isInstanceOf(com.miriyum.global.exception.ServiceException.class);
    }

    @Test
    void oldTrackingProofCannotBeReplayedAfterRecoveryCompletionWindow() {
        MemberSupportCrypto crypto = crypto();
        MemberIdentityVerification tracking = MemberIdentityVerification.recovery(
                crypto.digest("old-tracking"), MemberAccountType.CONSUMER, 41,
                crypto.encrypt("new@example.com"), crypto.digest("new@example.com"),
                LocalDateTime.now(CLOCK).minusDays(40), LocalDateTime.now(CLOCK).minusDays(39));
        ReflectionTestUtils.setField(tracking, "id", 7L);
        ReflectionTestUtils.setField(tracking, "consumedAt", LocalDateTime.now(CLOCK).minusDays(39));
        MemberSupportCase supportCase = approvedCase();
        ReflectionTestUtils.setField(supportCase, "decidedAt", LocalDateTime.now(CLOCK).minusDays(31));
        MemberIdentityVerificationRepository verifications = mock(MemberIdentityVerificationRepository.class);
        when(verifications.findByProofDigestForUpdate(crypto.digest("old-tracking")))
                .thenReturn(Optional.of(tracking));
        MemberSupportCaseRepository cases = mock(MemberSupportCaseRepository.class);
        when(cases.findByIdentityVerificationIdForUpdate(7L)).thenReturn(Optional.of(supportCase));
        MemberPasswordResetCredentialService service = new MemberPasswordResetCredentialService(
                cases, verifications, crypto, properties(), CLOCK);

        assertThat(service.exchange(MemberAccountType.CONSUMER, "old-tracking")).isEmpty();
        verify(verifications, org.mockito.Mockito.never()).save(any());
    }

    private MemberSupportCase approvedCase() {
        MemberSupportCase supportCase = MemberSupportCase.recovery(
                MemberAccountType.CONSUMER, 41, 7, 3, LocalDateTime.now(CLOCK).minusHours(2));
        supportCase.assign();
        supportCase.decide(MemberSupportCaseStatus.APPROVED, "APPROVED", LocalDateTime.now(CLOCK));
        return supportCase;
    }

    private MemberAccountSupportPort port() {
        MemberAccountSupportPort port = mock(MemberAccountSupportPort.class);
        when(port.accountType()).thenReturn(MemberAccountType.CONSUMER);
        return port;
    }

    private MemberSupportCrypto crypto() {
        return new MemberSupportCrypto("proof-secret", Base64.getEncoder().encodeToString(new byte[32]));
    }

    private MemberSupportProperties properties() {
        return new MemberSupportProperties(true, "proof-secret",
                Base64.getEncoder().encodeToString(new byte[32]),
                Duration.ofMinutes(15), Duration.ofMinutes(30), true);
    }
}
