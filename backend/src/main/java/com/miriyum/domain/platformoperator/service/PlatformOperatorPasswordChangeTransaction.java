package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.platformoperator.dto.auth.InitialPasswordChangeRequest;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAuthEvent;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventType;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAuthEventRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorPasswordChangeTransaction {
    private final PlatformOperatorAccountRepository accounts;
    private final PlatformOperatorAuthEventRepository events;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final Clock clock;

    public PlatformOperatorPasswordChangeTransaction(
            PlatformOperatorAccountRepository accounts, PlatformOperatorAuthEventRepository events,
            PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy, Clock clock) {
        this.accounts = accounts;
        this.events = events;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.clock = clock;
    }

    @Transactional
    public ChangedAccount change(Long accountId, InitialPasswordChangeRequest request) {
        PlatformOperatorAccount account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID));
        if (!request.newPassword().equals(request.newPasswordConfirm())) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (!matches(passwordPolicy.toNfc(request.currentPassword()), account.getPasswordHash())) {
            throw new ServiceException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        String normalized = passwordPolicy.normalize(request.newPassword());
        account.changeInitialPassword(passwordEncoder.encode(normalized));
        accounts.saveAndFlush(account);
        events.save(PlatformOperatorAuthEvent.record(account.getId(),
                PlatformOperatorAuthEventType.INITIAL_PASSWORD_CHANGED, account.getAuthorityVersion(),
                account.getSessionVersion(), PlatformOperatorAuthEventOutcome.SUCCESS,
                UUID.randomUUID().toString(), clock.instant()));
        return new ChangedAccount(account.getId(), account.getAuthorityVersion(), account.getSessionVersion());
    }

    private boolean matches(String raw, String encoded) {
        try { return passwordEncoder.matches(raw, encoded); }
        catch (IllegalArgumentException exception) { return false; }
    }

    public record ChangedAccount(Long accountId, long authorityVersion, long sessionVersion) {}
}
