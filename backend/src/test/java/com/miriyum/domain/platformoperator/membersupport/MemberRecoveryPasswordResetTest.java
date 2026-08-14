package com.miriyum.domain.platformoperator.membersupport;

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
import com.miriyum.domain.platformoperator.service.membersupport.MemberRecoveryService;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportCrypto;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MemberRecoveryPasswordResetTest {
    @Test
    void approvedProofReplacesPasswordWithoutIssuingLoginToken() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
        MemberSupportCrypto crypto = new MemberSupportCrypto(
                "proof-secret", Base64.getEncoder().encodeToString(new byte[32]));
        MemberIdentityVerification verification = MemberIdentityVerification.recovery(
                crypto.digest("raw-proof"), MemberAccountType.CONSUMER, 41,
                crypto.encrypt("new@example.com"), crypto.digest("new@example.com"),
                LocalDateTime.now(clock).minusMinutes(1), LocalDateTime.now(clock).plusMinutes(14));
        ReflectionTestUtils.setField(verification, "id", 7L);
        MemberSupportCase supportCase = MemberSupportCase.recovery(
                MemberAccountType.CONSUMER, 41, 7, 3, LocalDateTime.now(clock));
        supportCase.assign();
        supportCase.decide(MemberSupportCaseStatus.APPROVED, "APPROVED", LocalDateTime.now(clock));
        MemberIdentityVerificationRepository verifications = mock(MemberIdentityVerificationRepository.class);
        when(verifications.findByProofDigest(crypto.digest("raw-proof"))).thenReturn(Optional.of(verification));
        MemberSupportCaseRepository cases = mock(MemberSupportCaseRepository.class);
        when(cases.findByIdentityVerificationIdAndStatus(7L, MemberSupportCaseStatus.APPROVED))
                .thenReturn(Optional.of(supportCase));
        MemberAccountSupportPort port = mock(MemberAccountSupportPort.class);
        when(port.accountType()).thenReturn(MemberAccountType.CONSUMER);
        MemberRecoveryService service = new MemberRecoveryService(
                cases, verifications, crypto, new MemberAccountSupportRegistry(List.of(port)),
                null, null, null, clock);

        service.completePasswordReset("raw-proof", MemberAccountType.CONSUMER, "NewPassword1!");

        verify(port).replaceRecoveredPassword(41, "NewPassword1!");
    }
}
