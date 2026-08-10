package com.miriyum.domain.search.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StoreSearchCandidateLimitTest {

    @Test
    void acceptsCandidateLimitsWithinTheHardMaximum() {
        assertThat(new StoreSearchCandidateLimit(1).value()).isEqualTo(1);
        assertThat(new StoreSearchCandidateLimit(5_000).value()).isEqualTo(5_000);
    }

    @Test
    void rejectsCandidateLimitsOutsideTheHardMaximum() {
        assertThatThrownBy(() -> new StoreSearchCandidateLimit(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoreSearchCandidateLimit(5_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
