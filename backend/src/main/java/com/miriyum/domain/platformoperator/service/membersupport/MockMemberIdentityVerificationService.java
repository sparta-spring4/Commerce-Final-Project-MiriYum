package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.StoreRecoveryEvidencePort;
import com.miriyum.domain.auth.membersupport.MemberVerificationChannel;
import com.miriyum.domain.platformoperator.dto.membersupport.PublicMemberSupportRequests.ConsumedVerification;
import com.miriyum.domain.platformoperator.dto.membersupport.PublicMemberSupportRequests.OpaqueProof;
import com.miriyum.domain.platformoperator.dto.membersupport.PublicMemberSupportRequests.RecoveryVerificationCommand;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberIdentityVerification;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberVerificationPurpose;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberIdentityVerificationRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MockMemberIdentityVerificationService {
    private final MemberAccountSupportRegistry accounts;
    private final MemberIdentityVerificationRepository verifications;
    private final MemberSupportCrypto crypto;
    private final MemberSupportProperties properties;
    private final Clock clock;
    private final StoreRecoveryEvidencePort storeEvidence;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public MockMemberIdentityVerificationService(MemberAccountSupportRegistry accounts,
                                                  MemberIdentityVerificationRepository verifications,
                                                  MemberSupportCrypto crypto,
                                                  MemberSupportProperties properties,
                                                  Clock clock,
                                                  StoreRecoveryEvidencePort storeEvidence) {
        this.accounts = accounts;
        this.verifications = verifications;
        this.crypto = crypto;
        this.properties = properties;
        this.clock = clock;
        this.storeEvidence = storeEvidence;
    }

    public MockMemberIdentityVerificationService(MemberAccountSupportRegistry accounts,
                                                  MemberIdentityVerificationRepository verifications,
                                                  MemberSupportCrypto crypto,
                                                  MemberSupportProperties properties,
                                                  Clock clock) {
        this(accounts, verifications, crypto, properties, clock, (accountId, number) -> false);
    }

    @Transactional
    public OpaqueProof issueRecovery(MemberAccountType accountType, RecoveryVerificationCommand command) {
        String rawProof = randomProof();
        if (!properties.devStubEnabled()) return new OpaqueProof(rawProof);
        var accountPort = accounts.require(accountType);
        Optional<com.miriyum.domain.auth.membersupport.MemberAccountSnapshot> target =
                accountType == MemberAccountType.STORE_OPERATOR
                        ? accountPort.findRecoveryTarget(command.oldEmail(), command.registeredPhone(),
                                command.representativeName())
                        : accountPort.findRecoveryTarget(command.oldEmail(), command.registeredPhone());
        target
                .filter(account -> accountType != MemberAccountType.STORE_OPERATOR
                        || storeEvidence.matchesOwnedStore(account.accountId(), command.businessRegistrationNumber()))
                .ifPresent(account -> {
                    LocalDateTime now = LocalDateTime.now(clock);
                    verifications.save(MemberIdentityVerification.recovery(
                            crypto.digest(rawProof), accountType, account.accountId(),
                            crypto.encrypt(command.newEmail()), crypto.digest(command.newEmail()),
                            now, now.plus(properties.verificationTtl())));
                });
        return new OpaqueProof(rawProof);
    }

    @Transactional
    public Optional<Long> verifyAppeal(MemberAccountType accountType, long accountId, long sanctionId,
                                       MemberVerificationChannel channel, String contact) {
        if (!properties.devStubEnabled() || channel == null || contact == null) return Optional.empty();
        if (!accounts.require(accountType).matchesRegisteredContact(accountId, channel, contact))
            return Optional.empty();
        String rawProof = randomProof();
        LocalDateTime now = LocalDateTime.now(clock);
        MemberIdentityVerification verification = verifications.save(MemberIdentityVerification.appeal(
                crypto.digest(rawProof), accountType, accountId, sanctionId,
                now, now.plus(properties.verificationTtl())));
        return verification.consume(accountType, MemberVerificationPurpose.ACCOUNT_APPEAL, now)
                ? Optional.of(verification.getId()) : Optional.empty();
    }

    @Transactional
    public Optional<ConsumedVerification> consumeForSubmission(
            String rawProof, MemberAccountType accountType, MemberVerificationPurpose purpose) {
        if (rawProof == null || rawProof.isBlank()) return Optional.empty();
        LocalDateTime now = LocalDateTime.now(clock);
        return verifications.findByProofDigestForUpdate(crypto.digest(rawProof))
                .filter(verification -> verification.consume(accountType, purpose, now))
                .map(verification -> new ConsumedVerification(
                        verification.getId(), verification.getAccountId(),
                        crypto.decrypt(verification.getEncryptedNewEmail())));
    }

    private String randomProof() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
