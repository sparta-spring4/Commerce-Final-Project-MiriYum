package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.auth.membersupport.MemberAccountSnapshot;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportPort;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberVerificationChannel;
import com.miriyum.domain.auth.membersupport.StoreRecoveryEvidencePort;
import com.miriyum.domain.platformoperator.dto.membersupport.PublicMemberSupportRequests.RecoveryVerificationCommand;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberIdentityVerification;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberVerificationPurpose;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberIdentityVerificationRepository;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportCrypto;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportProperties;
import com.miriyum.domain.platformoperator.service.membersupport.MockMemberIdentityVerificationService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class MockMemberIdentityVerificationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsWithRandomNonceAndNeverPrintsSecrets() {
        MemberSupportCrypto crypto = new MemberSupportCrypto("proof-secret", ENCRYPTION_KEY);

        byte[] first = crypto.encrypt("new@example.com");
        byte[] second = crypto.encrypt("new@example.com");

        assertThat(first).isNotEqualTo(second);
        assertThat(crypto.decrypt(first)).isEqualTo("new@example.com");
        assertThat(crypto.digest("proof")).hasSize(64);
        var command = new RecoveryVerificationCommand("old@example.com", "+821012345678", "new@example.com");
        assertThat(command.toString()).doesNotContain("old@example.com", "+821012345678", "new@example.com");
    }

    @Test
    void validTargetPersistsDigestWhileMissingTargetReturnsIndistinguishableProof() {
        MemberIdentityVerificationRepository repository = mock(MemberIdentityVerificationRepository.class);
        MemberAccountSupportPort port = mock(MemberAccountSupportPort.class);
        when(port.accountType()).thenReturn(MemberAccountType.CONSUMER);
        when(port.findRecoveryTarget("old@example.com", "+821012345678"))
                .thenReturn(Optional.of(new MemberAccountSnapshot(
                        MemberAccountType.CONSUMER, 41, false, false,
                        Instant.parse("2026-08-01T00:00:00Z"), 0)));
        MemberSupportProperties properties = properties(true);
        MockMemberIdentityVerificationService service = new MockMemberIdentityVerificationService(
                new MemberAccountSupportRegistry(List.of(port)), repository,
                new MemberSupportCrypto(properties),
                properties, CLOCK);

        var valid = service.issueRecovery(MemberAccountType.CONSUMER,
                new RecoveryVerificationCommand("old@example.com", "+821012345678", "new@example.com"));
        when(port.findRecoveryTarget("missing@example.com", "+821099999999"))
                .thenReturn(Optional.empty());
        var missing = service.issueRecovery(MemberAccountType.CONSUMER,
                new RecoveryVerificationCommand("missing@example.com", "+821099999999", "new@example.com"));

        assertThat(valid.value()).hasSameSizeAs(missing.value());
        assertThat(valid.toString()).doesNotContain(valid.value());
        ArgumentCaptor<MemberIdentityVerification> saved = ArgumentCaptor.forClass(MemberIdentityVerification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getProofDigest()).doesNotContain(valid.value());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void disabledStubReturnsOpaqueValueWithoutPersisting() {
        MemberIdentityVerificationRepository repository = mock(MemberIdentityVerificationRepository.class);
        MemberSupportProperties properties = properties(false);
        MockMemberIdentityVerificationService service = new MockMemberIdentityVerificationService(
                mock(MemberAccountSupportRegistry.class), repository,
                new MemberSupportCrypto(properties),
                properties, CLOCK);

        var proof = service.issueRecovery(MemberAccountType.CONSUMER,
                new RecoveryVerificationCommand("old@example.com", "+821012345678", "new@example.com"));

        assertThat(proof.value()).hasSize(43);
        verify(repository, never()).save(any());
    }

    @Test
    void proofIsBoundToTypeAndPurposeAndCanBeConsumedOnce() {
        MemberIdentityVerificationRepository repository = mock(MemberIdentityVerificationRepository.class);
        MemberSupportProperties properties = properties(true);
        MemberSupportCrypto crypto = new MemberSupportCrypto(properties);
        MemberIdentityVerification verification = MemberIdentityVerification.recovery(
                crypto.digest("proof"), MemberAccountType.CONSUMER, 41,
                crypto.encrypt("new@example.com"), crypto.digest("new@example.com"),
                LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC),
                LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC).plusMinutes(15));
        ReflectionTestUtils.setField(verification, "id", 3L);
        when(repository.findByProofDigestForUpdate(crypto.digest("proof")))
                .thenReturn(Optional.of(verification));
        MockMemberIdentityVerificationService service = new MockMemberIdentityVerificationService(
                mock(MemberAccountSupportRegistry.class), repository, crypto, properties, CLOCK);

        assertThat(service.consumeForSubmission(
                "proof", MemberAccountType.STORE_OPERATOR, MemberVerificationPurpose.MEMBER_RECOVERY)).isEmpty();
        assertThat(service.consumeForSubmission(
                "proof", MemberAccountType.CONSUMER, MemberVerificationPurpose.MEMBER_RECOVERY))
                .get().extracting(result -> result.accountId()).isEqualTo(41L);
        assertThat(service.consumeForSubmission(
                "proof", MemberAccountType.CONSUMER, MemberVerificationPurpose.MEMBER_RECOVERY)).isEmpty();
    }

    @Test
    void storeRecoveryRequiresRepresentativeAndBusinessNumberForOneOwnedStore() {
        MemberIdentityVerificationRepository repository = mock(MemberIdentityVerificationRepository.class);
        MemberAccountSupportPort port = mock(MemberAccountSupportPort.class);
        when(port.accountType()).thenReturn(MemberAccountType.STORE_OPERATOR);
        var account = new MemberAccountSnapshot(MemberAccountType.STORE_OPERATOR, 71, false, false,
                Instant.parse("2026-08-01T00:00:00Z"), 0);
        when(port.findRecoveryTarget("store@example.com", "+821012345678", "대표자"))
                .thenReturn(Optional.of(account));
        StoreRecoveryEvidencePort evidence = mock(StoreRecoveryEvidencePort.class);
        when(evidence.matchesOwnedStore(71, "1234567890")).thenReturn(true);
        MemberSupportProperties properties = properties(true);
        MockMemberIdentityVerificationService service = new MockMemberIdentityVerificationService(
                new MemberAccountSupportRegistry(List.of(port)), repository,
                new MemberSupportCrypto(properties),
                properties, CLOCK, evidence);

        service.issueRecovery(MemberAccountType.STORE_OPERATOR,
                new RecoveryVerificationCommand("store@example.com", "+821012345678", "new@example.com",
                        "0000000000", "대표자"));
        verify(repository, never()).save(any());

        service.issueRecovery(MemberAccountType.STORE_OPERATOR,
                new RecoveryVerificationCommand("store@example.com", "+821012345678", "new@example.com",
                        "1234567890", "대표자"));
        verify(repository).save(any(MemberIdentityVerification.class));
    }

    @Test
    void appealEvidenceIsBoundToAccountAndSanctionAndConsumedImmediately() {
        MemberIdentityVerificationRepository repository = mock(MemberIdentityVerificationRepository.class);
        when(repository.save(any(MemberIdentityVerification.class))).thenAnswer(invocation -> {
            MemberIdentityVerification saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 17L);
            return saved;
        });
        MemberAccountSupportPort port = mock(MemberAccountSupportPort.class);
        when(port.accountType()).thenReturn(MemberAccountType.CONSUMER);
        when(port.matchesRegisteredContact(41, MemberVerificationChannel.REGISTERED_EMAIL, "registered@example.com"))
                .thenReturn(true);
        MemberSupportProperties properties = properties(true);
        MockMemberIdentityVerificationService service = new MockMemberIdentityVerificationService(
                new MemberAccountSupportRegistry(List.of(port)), repository,
                new MemberSupportCrypto(properties),
                properties, CLOCK, mock(StoreRecoveryEvidencePort.class));

        assertThat(service.verifyAppeal(MemberAccountType.CONSUMER, 41, 9,
                MemberVerificationChannel.REGISTERED_EMAIL, "wrong@example.com")).isEmpty();
        assertThat(service.verifyAppeal(MemberAccountType.CONSUMER, 41, 9,
                MemberVerificationChannel.REGISTERED_EMAIL, "registered@example.com")).contains(17L);

        ArgumentCaptor<MemberIdentityVerification> saved = ArgumentCaptor.forClass(MemberIdentityVerification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getAccountId()).isEqualTo(41);
        assertThat(saved.getValue().getSourceSanctionId()).isEqualTo(9);
        assertThat(saved.getValue().getConsumedAt()).isEqualTo(LocalDateTime.now(CLOCK));
        assertThat(saved.getValue().consume(MemberAccountType.CONSUMER,
                MemberVerificationPurpose.ACCOUNT_APPEAL, LocalDateTime.now(CLOCK))).isFalse();
    }

    private MemberSupportProperties properties(boolean stubEnabled) {
        return new MemberSupportProperties(true, "proof-secret", ENCRYPTION_KEY,
                java.time.Duration.ofMinutes(15), java.time.Duration.ofMinutes(30), stubEnabled);
    }
}
