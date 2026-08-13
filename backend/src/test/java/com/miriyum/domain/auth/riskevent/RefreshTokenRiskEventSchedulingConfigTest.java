package com.miriyum.domain.auth.riskevent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;

class RefreshTokenRiskEventSchedulingConfigTest {

    @Test
    void 위험사건_전달은_명시적으로_활성화한_환경에서만_스케줄링한다() {
        assertThat(RefreshTokenRiskEventSchedulingConfig.class)
                .hasAnnotation(EnableScheduling.class)
                .hasAnnotation(ConditionalOnProperty.class);
        assertThat(RefreshTokenRiskEventDelivery.class)
                .hasAnnotation(ConditionalOnProperty.class);
    }
}
