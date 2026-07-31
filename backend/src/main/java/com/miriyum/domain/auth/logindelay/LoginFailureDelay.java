package com.miriyum.domain.auth.logindelay;

import java.time.LocalDateTime;

/**
 * Stores only confirmed password failures for one account.
 */
public record LoginFailureDelay(
        int consecutiveFailures,
        int delayStage,
        LocalDateTime nextAttemptAllowedAt
) {

    public static LoginFailureDelay none() {
        return new LoginFailureDelay(0, 0, null);
    }

    public boolean isDelayedAt(LocalDateTime now) {
        return nextAttemptAllowedAt != null && now.isBefore(nextAttemptAllowedAt);
    }
}
