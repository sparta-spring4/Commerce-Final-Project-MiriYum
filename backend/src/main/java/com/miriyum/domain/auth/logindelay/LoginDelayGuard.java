package com.miriyum.domain.auth.logindelay;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the account-level login delay policy after a password mismatch is confirmed.
 */
@Component
public class LoginDelayGuard {

    private static final Duration ATTEMPT_LEASE = Duration.ofSeconds(30);

    private final LoginFailureDelayRepository loginFailureDelayRepository;
    private final LoginDelayPolicy loginDelayPolicy;
    private final Clock clock;

    public LoginDelayGuard(
            LoginFailureDelayRepository loginFailureDelayRepository,
            LoginDelayPolicy loginDelayPolicy,
            Clock clock
    ) {
        this.loginFailureDelayRepository = loginFailureDelayRepository;
        this.loginDelayPolicy = loginDelayPolicy;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LoginAttempt tryAcquireAttempt(TokenNamespace namespace, long accountId) {
        LocalDateTime now = LocalDateTime.now(clock);
        String token = UUID.randomUUID().toString();
        LocalDateTime expiresAt = now.plus(ATTEMPT_LEASE);

        if (loginFailureDelayRepository.insertAttempt(namespace.value(), accountId, token, expiresAt, now)
                || loginFailureDelayRepository.acquireExistingAttempt(
                namespace.value(), accountId, token, expiresAt, now)) {
            return LoginAttempt.acquired(token);
        }

        return loginFailureDelayRepository.find(namespace.value(), accountId)
                .filter(delay -> delay.isDelayedAt(now))
                .map(delay -> LoginAttempt.delayed())
                .orElseGet(LoginAttempt::busy);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeAttempt(
            TokenNamespace namespace,
            long accountId,
            LoginAttempt attempt,
            boolean passwordMatches
    ) {
        LocalDateTime now = LocalDateTime.now(clock);
        LoginFailureDelay current = loginFailureDelayRepository.lockExisting(namespace.value(), accountId);
        if (!current.isOwnedBy(attempt.token())) {
            return false;
        }
        if (passwordMatches) {
            loginFailureDelayRepository.resetOwnedAttempt(namespace.value(), accountId, attempt.token());
            return true;
        }
        LoginFailureDelay updated = loginDelayPolicy.applyFailure(current, now);
        loginFailureDelayRepository.save(namespace.value(), accountId, updated, now);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseAttempt(TokenNamespace namespace, long accountId, LoginAttempt attempt) {
        loginFailureDelayRepository.releaseAttempt(
                namespace.value(), accountId, attempt.token(), LocalDateTime.now(clock));
    }
}
