package com.miriyum.global.sse;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** SSE 설정 binding과 enabled 환경의 전용 scheduler·Valkey subscription을 구성한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SseRuntimeProperties.class)
public class SseRuntimeConfig {

    private static final long SAFE_DISABLED_DELAY_MILLIS = 60_000L;

    @Bean("sseCorrectionDelayMs")
    Long sseCorrectionDelayMs(SseRuntimeProperties properties) {
        return safeDelay(properties.correctionInterval());
    }

    @Bean("sseHeartbeatDelayMs")
    Long sseHeartbeatDelayMs(SseRuntimeProperties properties) {
        return safeDelay(properties.heartbeatInterval());
    }

    @Bean(name = "sseTaskScheduler", destroyMethod = "shutdown", defaultCandidate = false)
    @ConditionalOnProperty(name = "miriyum.sse.enabled", havingValue = "true")
    ThreadPoolTaskScheduler sseTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("sse-runtime-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    @Bean(destroyMethod = "destroy")
    @ConditionalOnProperty(name = "miriyum.sse.enabled", havingValue = "true")
    RedisMessageListenerContainer sseRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            SseWakeUpBroker broker
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(broker, new ChannelTopic(SseWakeUpBroker.CHANNEL));
        return container;
    }

    private static long safeDelay(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return SAFE_DISABLED_DELAY_MILLIS;
        }
        try {
            return Math.max(1L, duration.toMillis());
        } catch (ArithmeticException overflow) {
            return SAFE_DISABLED_DELAY_MILLIS;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "miriyum.sse.enabled", havingValue = "true")
    static class SchedulingActivation {
    }
}
