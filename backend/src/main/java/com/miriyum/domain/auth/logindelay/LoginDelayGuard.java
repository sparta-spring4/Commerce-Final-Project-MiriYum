package com.miriyum.domain.auth.logindelay;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Applies the account-level login delay policy after a password mismatch is confirmed.
 */
@Component
public class LoginDelayGuard {

    private static final Duration ATTEMPT_LEASE = Duration.ofSeconds(30);

    private final LoginFailureDelayRepository loginFailureDelayRepository;
    private final LoginDelayPolicy loginDelayPolicy;
    private final Clock clock;
    private final LoginDelayTransactionExecutor transactionExecutor;

    public LoginDelayGuard(
            LoginFailureDelayRepository loginFailureDelayRepository,
            LoginDelayPolicy loginDelayPolicy,
            Clock clock,
            LoginDelayTransactionExecutor transactionExecutor
    ) {
        this.loginFailureDelayRepository = loginFailureDelayRepository;
        this.loginDelayPolicy = loginDelayPolicy;
        this.clock = clock;
        this.transactionExecutor = transactionExecutor;
    }

    public LoginAttempt tryAcquireAttempt(TokenNamespace namespace, long accountId) {
        try {
            return transactionExecutor.execute(() -> acquireAttempt(namespace, accountId));
        } catch (PessimisticLockingFailureException exception) {
            return LoginAttempt.busy();
        }
    }

    private LoginAttempt acquireAttempt(TokenNamespace namespace, long accountId) {
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

    public boolean completeAttempt(
            TokenNamespace namespace,
            long accountId,
            LoginAttempt attempt,
            boolean passwordMatches
    ) {
        try {
            return transactionExecutor.execute(() -> completeAttemptInTransaction(
                    namespace, accountId, attempt, passwordMatches));
        } catch (PessimisticLockingFailureException exception) {
            return false;
        }
    }

    private boolean completeAttemptInTransaction(
            TokenNamespace namespace,
            long accountId,
            LoginAttempt attempt,
            boolean passwordMatches
    ) {
        LocalDateTime now = LocalDateTime.now(clock);
        LoginFailureDelay current = loginFailureDelayRepository.lockExisting(namespace.value(), accountId)
                .orElse(null);
        if (current == null) {
            return false;
        }
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

    public void releaseAttempt(TokenNamespace namespace, long accountId, LoginAttempt attempt) {
        try {
            transactionExecutor.execute(() -> {
                loginFailureDelayRepository.releaseAttempt(
                        namespace.value(), accountId, attempt.token(), LocalDateTime.now(clock));
                return true;
            });
        } catch (PessimisticLockingFailureException ignored) {
            // The lease expires after 30 seconds; do not mask the original login failure.
        }
    }
}
