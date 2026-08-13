package com.miriyum.domain.auth.riskevent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class RefreshTokenRiskEventSchedulingConfigTest {

    @Test
    void 위험_사건_전달은_활성화된_환경에서만_전용_스케줄러를_사용한다() throws NoSuchMethodException {
        assertThat(RefreshTokenRiskEventSchedulingConfig.WorkerSchedulingActivation.class)
                .hasAnnotation(EnableScheduling.class)
                .hasAnnotation(ConditionalOnProperty.class);
        Bean schedulerBean = RefreshTokenRiskEventSchedulingConfig.class
                .getDeclaredMethod("refreshTokenRiskEventTaskScheduler")
                .getAnnotation(Bean.class);
        assertThat(schedulerBean.name()).containsExactly("refreshTokenRiskEventTaskScheduler");
        ThreadPoolTaskScheduler scheduler = new RefreshTokenRiskEventSchedulingConfig()
                .refreshTokenRiskEventTaskScheduler();
        assertThat(scheduler.getPoolSize()).isEqualTo(1);
        assertThat(scheduler.getThreadNamePrefix()).isEqualTo("refresh-risk-event-");

        assertThat(RefreshTokenRiskEventDelivery.class)
                .hasAnnotation(ConditionalOnProperty.class);
        Scheduled scheduled = RefreshTokenRiskEventDelivery.class
                .getMethod("deliverPendingEventsOnSchedule")
                .getAnnotation(Scheduled.class);
        assertThat(scheduled.scheduler()).isEqualTo("refreshTokenRiskEventTaskScheduler");
    }
}
