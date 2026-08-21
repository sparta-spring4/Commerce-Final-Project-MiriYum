package com.miriyum.domain.store.onboarding.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.DecisionType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StoreOnboardingDecisionTest {
    @Test
    void decisionPreservesTheCommandIdempotencyIdentity() {
        var decision = StoreOnboardingDecision.record(
                "550e8400-e29b-41d4-a716-446655440277", 2L, 91L,
                DecisionType.APPROVE, "APPROVED", null,
                "550e8400-e29b-41d4-a716-446655440001",
                Instant.parse("2026-08-21T00:00:00Z"));

        assertThat(decision.getIdempotencyKey())
                .isEqualTo("550e8400-e29b-41d4-a716-446655440001");
    }
}
