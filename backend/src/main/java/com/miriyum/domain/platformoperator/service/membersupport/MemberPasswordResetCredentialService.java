package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.platformoperator.dto.membersupport.PublicMemberSupportRequests.OpaqueProof;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberIdentityVerification;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCaseStatus;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberVerificationPurpose;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberIdentityVerificationRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberPasswordResetCredentialService {
    private final MemberSupportCaseRepository cases;
    private final MemberIdentityVerificationRepository verifications;
    private final MemberSupportCrypto crypto;
    private final MemberSupportProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public MemberPasswordResetCredentialService(
            MemberSupportCaseRepository cases,
            MemberIdentityVerificationRepository verifications,
            MemberSupportCrypto crypto,
            MemberSupportProperties properties,
            Clock clock
    ) {
        this.cases = cases;
        this.verifications = verifications;
        this.crypto = crypto;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Optional<OpaqueProof> exchange(MemberAccountType accountType, String trackingProof) {
        if (trackingProof == null || trackingProof.isBlank()) return Optional.empty();
        LocalDateTime now = LocalDateTime.now(clock);
        return verifications.findByProofDigestForUpdate(crypto.digest(trackingProof))
                .filter(verification -> verification.getAccountType() == accountType)
                .filter(verification -> verification.getPurpose() == MemberVerificationPurpose.MEMBER_RECOVERY)
                .filter(verification -> verification.getConsumedAt() != null)
                .flatMap(verification -> cases.findByIdentityVerificationIdForUpdate(verification.getId())
                        .filter(supportCase -> supportCase.getStatus() == MemberSupportCaseStatus.APPROVED)
                        .filter(supportCase -> supportCase.getAccountType() == accountType)
                        .filter(supportCase -> supportCase.getAccountId() == verification.getAccountId())
                        .filter(supportCase -> supportCase.getPasswordResetCompletedAt() == null)
                        .filter(supportCase -> supportCase.getPasswordResetVerificationId() == null)
                        .filter(supportCase -> supportCase.getDecidedAt() != null)
                        .filter(supportCase -> supportCase.getDecidedAt()
                                .plus(properties.recoveryCompletionTtl()).isAfter(now))
                        .map(supportCase -> issue(supportCase, accountType)));
    }

    public OpaqueProof placeholder() {
        return new OpaqueProof(randomProof());
    }

    private OpaqueProof issue(
            com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase supportCase,
            MemberAccountType accountType
    ) {
        String rawProof = randomProof();
        LocalDateTime now = LocalDateTime.now(clock);
        MemberIdentityVerification reset = verifications.save(MemberIdentityVerification.passwordReset(
                crypto.digest(rawProof), accountType, supportCase.getAccountId(),
                now, now.plus(properties.verificationTtl())));
        supportCase.issuePasswordResetVerification(reset.getId());
        return new OpaqueProof(rawProof);
    }

    private String randomProof() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
