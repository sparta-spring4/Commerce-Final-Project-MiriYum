package com.miriyum.domain.auth.logindelay;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the account-level login delay policy after a password mismatch is confirmed.
 */
@Component
public class LoginDelayGuard {

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

    /**
     * Reads an already-confirmed delay without creating or updating a failure row.
     */
    @Transactional(readOnly = true)
    public boolean isDelayed(TokenNamespace namespace, long accountId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return loginFailureDelayRepository.find(namespace.value(), accountId)
                .map(delay -> delay.isDelayedAt(now))
                .orElse(false);
    }

    /**
     * Updates the policy only after PasswordEncoder has confirmed a mismatch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(TokenNamespace namespace, long accountId) {
        LocalDateTime now = LocalDateTime.now(clock);
        LoginFailureDelay current = loginFailureDelayRepository.lock(namespace.value(), accountId, now);
        LoginFailureDelay updated = loginDelayPolicy.applyFailure(current, now);
        loginFailureDelayRepository.save(namespace.value(), accountId, updated, now);
    }

    /**
     * A successful password comparison clears previously confirmed failures.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(TokenNamespace namespace, long accountId) {
        loginFailureDelayRepository.reset(namespace.value(), accountId);
    }
}
