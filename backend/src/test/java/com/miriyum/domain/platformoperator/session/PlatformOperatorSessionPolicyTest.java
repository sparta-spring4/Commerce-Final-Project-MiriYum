package com.miriyum.domain.platformoperator.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PlatformOperatorSessionPolicyTest {
    private final PlatformOperatorSessionPolicy policy = new PlatformOperatorSessionPolicy();
    private final Instant now = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void fixesIdleAndAbsoluteExpirationsAndCapsTouchAtAbsoluteBoundary() {
        Instant absolute = policy.absoluteExpiresAt(now);
        assertThat(policy.idleExpiresAt(now)).isEqualTo(Instant.parse("2026-08-13T00:30:00Z"));
        assertThat(absolute).isEqualTo(Instant.parse("2026-08-13T08:00:00Z"));
        assertThat(policy.nextExpiresAt(Instant.parse("2026-08-13T07:50:00Z"), absolute))
                .isEqualTo(absolute);
        assertThat(policy.isExpired(absolute, absolute)).isTrue();
    }
}
