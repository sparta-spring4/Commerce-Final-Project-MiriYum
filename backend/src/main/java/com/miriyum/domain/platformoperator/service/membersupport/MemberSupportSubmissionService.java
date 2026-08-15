package com.miriyum.domain.platformoperator.service.membersupport;

import com.miriyum.domain.auth.membersupport.MemberAccountSupportRegistry;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberVerificationChannel;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSupportCase;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberVerificationPurpose;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSupportCaseRepository;
import com.miriyum.domain.platformoperator.repository.membersupport.MemberSanctionRepository;
import com.miriyum.domain.platformoperator.entity.membersupport.MemberSanctionStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public class MemberSupportSubmissionService {
    private final MockMemberIdentityVerificationService verifications;
    private final MemberAccountSupportRegistry accounts;
    private final MemberSupportCaseRepository cases;
    private final MemberSanctionRepository sanctions;
    private final Clock clock;

    public MemberSupportSubmissionService(MockMemberIdentityVerificationService verifications,
                                          MemberAccountSupportRegistry accounts,
                                          MemberSupportCaseRepository cases,
                                          MemberSanctionRepository sanctions,
                                          Clock clock) {
        this.verifications = verifications;
        this.accounts = accounts;
        this.cases = cases;
        this.sanctions = sanctions;
        this.clock = clock;
    }

    @Transactional
    public void submitAppeal(MemberAccountType accountType, String sanctionPublicId,
                             MemberVerificationChannel channel, String contact, String ignoredStatement) {
        if (sanctionPublicId == null || sanctionPublicId.isBlank()) return;
        sanctions.findByPublicId(sanctionPublicId)
                .filter(sanction -> sanction.getAccountType() == accountType)
                .filter(sanction -> sanction.getStatus() == MemberSanctionStatus.APPLIED)
                .flatMap(sanction -> verifications.verifyAppeal(accountType, sanction.getAccountId(),
                                sanction.getId(), channel, contact)
                        .flatMap(verificationId -> accounts.require(accountType).findMinimal(sanction.getAccountId())
                                .map(account -> MemberSupportCase.appeal(
                                        accountType, account.accountId(), sanction.getId(), verificationId,
                                        account.supportVersion(), LocalDateTime.now(clock)))))
                .ifPresent(cases::save);
    }

    @Transactional
    public void submitRecovery(MemberAccountType accountType, String rawProof, String submittedNewEmail) {
        verifications.consumeForSubmission(rawProof, accountType, MemberVerificationPurpose.MEMBER_RECOVERY)
                .filter(consumed -> equal(consumed.newEmail(), submittedNewEmail))
                .flatMap(consumed -> accounts.require(accountType).findMinimal(consumed.accountId())
                        .map(account -> MemberSupportCase.recovery(
                                accountType, account.accountId(), consumed.verificationId(),
                                account.supportVersion(), LocalDateTime.now(clock))))
                .ifPresent(cases::save);
    }

    private boolean equal(String first, String second) {
        if (first == null || second == null) return false;
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }
}
