package com.miriyum.domain.auth.riskevent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class RefreshTokenRiskEventSchedulingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(RefreshTokenRiskEventSchedulingConfig.class)
            .withPropertyValues("miriyum.auth.refresh-risk-event-delivery.enabled=true");

    private final ApplicationContextRunner disabledContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(RefreshTokenRiskEventSchedulingConfig.class);

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

    @Test
    void 위험_사건_전달이_활성화돼도_기본_스케줄러를_유지한다() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("refreshTokenRiskEventTaskScheduler");
            assertThat(context).hasBean("taskScheduler");
        });
    }

    @Test
    void 위험_사건_전달이_비활성화된_환경에서는_전용_스케줄러를_등록하지_않는다() {
        disabledContextRunner.run(context ->
                assertThat(context).doesNotHaveBean("refreshTokenRiskEventTaskScheduler"));
        disabledContextRunner
                .withPropertyValues("miriyum.auth.refresh-risk-event-delivery.enabled=false")
                .run(context ->
                        assertThat(context).doesNotHaveBean("refreshTokenRiskEventTaskScheduler"));
    }
}
