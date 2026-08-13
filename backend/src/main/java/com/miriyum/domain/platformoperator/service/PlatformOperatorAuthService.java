package com.miriyum.domain.platformoperator.service;

import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.SessionTokenClaims;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.logindelay.LoginAttempt;
import com.miriyum.domain.auth.logindelay.LoginDelayGuard;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.platformoperator.config.PlatformOperatorAuthProperties;
import com.miriyum.domain.platformoperator.dto.auth.InitialPasswordChangeRequest;
import com.miriyum.domain.platformoperator.dto.auth.PlatformOperatorTokenResult;
import com.miriyum.domain.platformoperator.entity.PlatformOperatorAccount;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAccountStatus;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorPasswordState;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventOutcome;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuthEventType;
import com.miriyum.domain.platformoperator.repository.PlatformOperatorAccountRepository;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.platformoperator.session.PlatformOperatorSessionManager;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorAuthService {
    private final PlatformOperatorAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final LoginDelayGuard delayGuard;
    private final PlatformOperatorSessionManager sessions;
    private final PlatformOperatorPasswordChangeTransaction passwordChange;
    private final PlatformOperatorAuthEventRecorder events;
    private final PlatformOperatorAuthProperties properties;
    private final Clock clock;
    private final String dummyPasswordHash;

    public PlatformOperatorAuthService(
            PlatformOperatorAccountRepository accounts, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
            LoginDelayGuard delayGuard, PlatformOperatorSessionManager sessions,
            PlatformOperatorPasswordChangeTransaction passwordChange,
            PlatformOperatorAuthEventRecorder events, PlatformOperatorAuthProperties properties, Clock clock) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.delayGuard = delayGuard;
        this.sessions = sessions;
        this.passwordChange = passwordChange;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("platform-operator-non-account-verification");
    }

    public PlatformOperatorTokenResult login(LoginRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT);
        PlatformOperatorAccount account = accounts.findByEmail(email).orElse(null);
        if (account == null) {
            verifyUnknownCredential(request.password());
            throw invalidCredentials();
        }
        try {
            return loginKnownAccount(request, account);
        } catch (ServiceException exception) {
            events.record(account, PlatformOperatorAuthEventType.LOGIN, PlatformOperatorAuthEventOutcome.FAILURE);
            throw exception;
        }
    }

    private void verifyUnknownCredential(String rawPassword) {
        long syntheticAccountId = Long.MIN_VALUE;
        LoginAttempt attempt = delayGuard.tryAcquireAttempt(TokenNamespace.PLATFORM_OPERATOR, syntheticAccountId);
        if (attempt.status() != LoginAttempt.Status.ACQUIRED) {
            matches(passwordPolicy.toNfc(rawPassword), dummyPasswordHash);
            return;
        }
        boolean completed = false;
        try {
            matches(passwordPolicy.toNfc(rawPassword), dummyPasswordHash);
            completed = true;
            delayGuard.completeAttempt(TokenNamespace.PLATFORM_OPERATOR, syntheticAccountId, attempt, false);
        } finally {
            if (!completed) delayGuard.releaseAttempt(TokenNamespace.PLATFORM_OPERATOR, syntheticAccountId, attempt);
        }
    }

    private PlatformOperatorTokenResult loginKnownAccount(LoginRequest request, PlatformOperatorAccount account) {
        LoginAttempt attempt = delayGuard.tryAcquireAttempt(TokenNamespace.PLATFORM_OPERATOR, account.getId());
        if (attempt.status() != LoginAttempt.Status.ACQUIRED) throw invalidCredentials();
        boolean completed = false;
        try {
            boolean matches = matches(passwordPolicy.toNfc(request.password()), account.getPasswordHash());
            completed = true;
            boolean attemptCompleted = delayGuard.completeAttempt(
                    TokenNamespace.PLATFORM_OPERATOR, account.getId(), attempt, matches);
            if (!matches && account.getPasswordState() == PlatformOperatorPasswordState.TEMPORARY) {
                accounts.incrementTemporaryPasswordFailure(account.getId());
            }
            if (!attemptCompleted || !matches) throw invalidCredentials();
            PlatformOperatorAccount current = accounts.findById(account.getId()).orElseThrow(this::invalidCredentials);
            if (current.getStatus() != PlatformOperatorAccountStatus.ACTIVE) {
                sessions.revokeAll(current.getId());
                throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
            }
            boolean temporary = current.getPasswordState() == PlatformOperatorPasswordState.TEMPORARY;
            if (temporary && !current.canUseTemporaryPassword(
                    clock.instant(), properties.getTemporaryPassword().getMaxFailures())) throw invalidCredentials();
            PlatformOperatorTokenResult result = sessions.issue(
                    current.getId(), current.getAuthorityVersion(), current.getSessionVersion(), temporary);
            events.record(current, PlatformOperatorAuthEventType.LOGIN, PlatformOperatorAuthEventOutcome.SUCCESS);
            return result;
        } finally {
            if (!completed) delayGuard.releaseAttempt(TokenNamespace.PLATFORM_OPERATOR, account.getId(), attempt);
        }
    }

    public PlatformOperatorTokenResult refresh(String rawRefreshToken) {
        ParsedToken parsed = sessions.parseRefresh(rawRefreshToken);
        PlatformOperatorAccount account = requireCurrentAccount(parsed, AuthErrorCode.REFRESH_TOKEN_INVALID);
        try {
            PlatformOperatorTokenResult result = sessions.rotate(parsed, rawRefreshToken);
            events.record(account, PlatformOperatorAuthEventType.REFRESH, PlatformOperatorAuthEventOutcome.SUCCESS);
            return result;
        } catch (ServiceException exception) {
            events.record(account, PlatformOperatorAuthEventType.REFRESH, PlatformOperatorAuthEventOutcome.FAILURE);
            throw exception;
        }
    }

    public void logout(String rawRefreshToken) { sessions.logout(rawRefreshToken); }

    public PlatformOperatorTokenResult changeInitialPassword(
            PlatformOperatorPrincipal principal, InitialPasswordChangeRequest request) {
        if (!principal.passwordChangeRequired()) {
            throw new ServiceException(com.miriyum.global.exception.CommonErrorCode.CONCURRENT_MODIFICATION);
        }
        var changed = passwordChange.change(principal.accountId(), request);
        sessions.revokeAll(changed.accountId());
        events.record(changed.accountId(), changed.authorityVersion(), changed.sessionVersion(),
                PlatformOperatorAuthEventType.SESSION_REVOKED, PlatformOperatorAuthEventOutcome.SUCCESS);
        return sessions.issue(changed.accountId(), changed.authorityVersion(), changed.sessionVersion(), false);
    }

    public PlatformOperatorPrincipal authenticateAccess(String rawAccessToken) {
        ParsedToken parsed = sessions.parseAccess(rawAccessToken);
        PlatformOperatorAccount account = requireCurrentAccount(parsed, AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID);
        sessions.validateAndTouch(parsed);
        SessionTokenClaims claims = parsed.sessionClaims();
        return new PlatformOperatorPrincipal(account.getId(), account.getEmail(), claims.sessionId(),
                claims.authorityVersion(), claims.sessionVersion(), claims.passwordChangeRequired());
    }

    private PlatformOperatorAccount requireCurrentAccount(ParsedToken parsed, AuthErrorCode invalidCode) {
        PlatformOperatorAccount account = accounts.findById(parsed.accountId())
                .orElseThrow(() -> new ServiceException(invalidCode));
        if (account.getStatus() != PlatformOperatorAccountStatus.ACTIVE) {
            sessions.revokeAll(parsed.accountId());
            events.record(account, PlatformOperatorAuthEventType.SESSION_REVOKED,
                    PlatformOperatorAuthEventOutcome.SUCCESS);
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }
        SessionTokenClaims claims = parsed.sessionClaims();
        if (claims.authorityVersion() != account.getAuthorityVersion()
                || claims.sessionVersion() != account.getSessionVersion()
                || claims.passwordChangeRequired()
                != (account.getPasswordState() == PlatformOperatorPasswordState.TEMPORARY)) {
            sessions.revoke(parsed);
            events.record(account, PlatformOperatorAuthEventType.SESSION_REVOKED,
                    PlatformOperatorAuthEventOutcome.SUCCESS);
            throw new ServiceException(invalidCode);
        }
        return account;
    }

    private boolean matches(String raw, String encoded) {
        try { return passwordEncoder.matches(raw, encoded); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private ServiceException invalidCredentials() { return new ServiceException(AuthErrorCode.INVALID_CREDENTIALS); }
}
