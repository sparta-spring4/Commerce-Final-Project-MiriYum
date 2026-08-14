package com.miriyum.domain.consumer.membersupport;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.membersupport.MemberAccountPage;
import com.miriyum.domain.auth.membersupport.MemberAccountSnapshot;
import com.miriyum.domain.auth.membersupport.MemberAccountSupportPort;
import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSearchCriteria;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenManager;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
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
public class ConsumerMemberSupportAdapter implements MemberAccountSupportPort {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final ConsumerMemberSupportRepository repository;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenManager refreshTokens;

    public ConsumerMemberSupportAdapter(
            ConsumerMemberSupportRepository repository,
            PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder,
            RefreshTokenManager refreshTokens
    ) {
        this.repository = repository;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
    }

    @Override public MemberAccountType accountType() { return MemberAccountType.CONSUMER; }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberAccountSnapshot> findMinimal(long accountId) {
        return repository.findById(accountId).map(this::snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberAccountSnapshot> findRecoveryTarget(String oldEmail, String registeredPhone) {
        return repository.findByEmailAndPhone(oldEmail, registeredPhone).map(this::snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public MemberAccountPage search(MemberSearchCriteria criteria, int offset, int limit) {
        if (offset < 0 || limit < 1 || offset % limit != 0) throw new IllegalArgumentException("invalid page window");
        var page = repository.search(
                local(criteria.joinedFrom()), local(criteria.joinedTo()), PageRequest.of(offset / limit, limit));
        return new MemberAccountPage(page.map(this::snapshot).getContent(), page.getTotalElements());
    }

    @Override
    @Transactional
    public long approveRecovery(long accountId, long expectedVersion, String newEmail) {
        ConsumerAccount account = requireLocked(accountId);
        account.assertSupportVersion(expectedVersion);
        account.approveRecovery(newEmail);
        refreshTokens.revokeAll(TokenNamespace.CONSUMER, accountId);
        return account.getSupportVersion();
    }

    @Override
    @Transactional
    public long applySuspension(long accountId, long expectedVersion) {
        ConsumerAccount account = requireLocked(accountId);
        account.assertSupportVersion(expectedVersion);
        account.applySupportSuspension();
        refreshTokens.revokeAll(TokenNamespace.CONSUMER, accountId);
        return account.getSupportVersion();
    }

    @Override
    @Transactional
    public long clearSuspension(long accountId, long expectedVersion) {
        ConsumerAccount account = requireLocked(accountId);
        account.assertSupportVersion(expectedVersion);
        account.clearSupportSuspension();
        return account.getSupportVersion();
    }

    @Override
    @Transactional
    public void replaceRecoveredPassword(long accountId, String newPassword) {
        ConsumerAccount account = requireLocked(accountId);
        String normalized = passwordPolicy.normalize(newPassword);
        account.replaceRecoveredPassword(passwordEncoder.encode(normalized));
        refreshTokens.revokeAll(TokenNamespace.CONSUMER, accountId);
    }

    private ConsumerAccount requireLocked(long accountId) {
        return repository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.MEMBER_SUPPORT_NOT_FOUND));
    }

    private MemberAccountSnapshot snapshot(ConsumerAccount account) {
        return new MemberAccountSnapshot(
                accountType(), account.getId(), account.isPasswordResetRequired(),
                account.getStatus() == ConsumerAccountStatus.SUSPENDED,
                account.getCreatedAt().atZone(BUSINESS_ZONE).toInstant(), account.getSupportVersion());
    }

    private static LocalDateTime local(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, BUSINESS_ZONE);
    }
}
