package com.miriyum.domain.storeoperator.membersupport;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.membersupport.MemberAccountPage;
import com.miriyum.domain.auth.membersupport.MemberAccountSnapshot;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportPort;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSearchCriteria;
import com.miriyum.domain.auth.membersupport.MemberStatus;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenManager;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.enums.StoreOperatorAccountStatus;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StoreOperatorMemberSupportAdapter implements MemberAccountSupportPort {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final StoreOperatorMemberSupportRepository repository;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenManager refreshTokens;

    public StoreOperatorMemberSupportAdapter(
            StoreOperatorMemberSupportRepository repository,
            PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder,
            RefreshTokenManager refreshTokens
    ) {
        this.repository = repository;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
    }

    @Override public MemberAccountType accountType() { return MemberAccountType.STORE_OPERATOR; }

    @Override @Transactional(readOnly = true)
    public Optional<MemberAccountSnapshot> findMinimal(long accountId) {
        return repository.findById(accountId).map(this::snapshot);
    }

    @Override @Transactional(readOnly = true)
    public Optional<MemberAccountSnapshot> findRecoveryTarget(String oldEmail, String registeredPhone) {
        return repository.findByEmailAndPhone(oldEmail, registeredPhone).map(this::snapshot);
    }

    @Override @Transactional(readOnly = true)
    public MemberAccountPage search(MemberSearchCriteria criteria, MemberStatus status, int offset, int limit) {
        if (offset < 0 || limit < 1 || offset % limit != 0) throw new IllegalArgumentException("invalid page window");
        var page = repository.search(
                local(criteria.joinedFrom()), local(criteria.joinedTo()), accountStatus(status),
                passwordResetRequired(status), PageRequest.of(offset / limit, limit));
        return new MemberAccountPage(page.map(this::snapshot).getContent(), page.getTotalElements());
    }

    @Override @Transactional
    public long approveRecovery(long accountId, long expectedVersion, String newEmail) {
        StoreOperatorAccount account = requireLocked(accountId);
        account.assertSupportVersion(expectedVersion);
        account.approveRecovery(newEmail);
        refreshTokens.revokeAll(TokenNamespace.STORE_OPERATOR, accountId);
        return account.getSupportVersion();
    }

    @Override @Transactional
    public long applySuspension(long accountId, long expectedVersion) {
        StoreOperatorAccount account = requireLocked(accountId);
        account.assertSupportVersion(expectedVersion);
        account.applySupportSuspension();
        refreshTokens.revokeAll(TokenNamespace.STORE_OPERATOR, accountId);
        return account.getSupportVersion();
    }

    @Override @Transactional
    public long clearSuspension(long accountId, long expectedVersion) {
        StoreOperatorAccount account = requireLocked(accountId);
        account.assertSupportVersion(expectedVersion);
        account.clearSupportSuspension();
        return account.getSupportVersion();
    }

    @Override @Transactional
    public void replaceRecoveredPassword(long accountId, String newPassword) {
        StoreOperatorAccount account = requireLocked(accountId);
        String normalized = passwordPolicy.normalize(newPassword);
        account.replaceRecoveredPassword(passwordEncoder.encode(normalized));
        refreshTokens.revokeAll(TokenNamespace.STORE_OPERATOR, accountId);
    }

    private StoreOperatorAccount requireLocked(long accountId) {
        return repository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
    }

    private MemberAccountSnapshot snapshot(StoreOperatorAccount account) {
        return new MemberAccountSnapshot(
                accountType(), account.getId(), account.isPasswordResetRequired(),
                account.getStatus() == StoreOperatorAccountStatus.SUSPENDED,
                account.getCreatedAt().atZone(BUSINESS_ZONE).toInstant(), account.getSupportVersion());
    }

    private static LocalDateTime local(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, BUSINESS_ZONE);
    }

    private static StoreOperatorAccountStatus accountStatus(MemberStatus status) {
        if (status == null) return null;
        return status == MemberStatus.TEMPORARILY_SUSPENDED
                ? StoreOperatorAccountStatus.SUSPENDED : StoreOperatorAccountStatus.ACTIVE;
    }

    private static Boolean passwordResetRequired(MemberStatus status) {
        if (status == null || status == MemberStatus.TEMPORARILY_SUSPENDED) return null;
        return status == MemberStatus.PASSWORD_RESET_REQUIRED;
    }
}
