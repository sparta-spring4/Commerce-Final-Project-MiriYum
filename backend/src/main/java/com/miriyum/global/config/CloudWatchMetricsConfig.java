package com.miriyum.global.config;

import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.config.MeterFilter;
import java.time.Duration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** CloudWatch에는 LLM 검색 운영 지표만 내보내 비용과 차원 수를 제한한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CloudWatchMetricsConfig.Properties.class)
@ConditionalOnProperty(prefix = "miriyum.cloudwatch-metrics", name = "enabled", havingValue = "true")
public class CloudWatchMetricsConfig {

    @Bean(destroyMethod = "close")
    CloudWatchAsyncClient cloudWatchAsyncClient(Properties properties) {
        return CloudWatchAsyncClient.builder()
                .region(Region.of(properties.region()))
                .build();
    }

    @Bean(destroyMethod = "close")
    CloudWatchMeterRegistry cloudWatchMeterRegistry(
            Properties properties,
            CloudWatchAsyncClient cloudWatchAsyncClient
    ) {
        CloudWatchConfig cloudWatchConfig = key -> switch (key) {
            case "cloudwatch.namespace" -> properties.namespace();
            case "cloudwatch.step" -> properties.step().toString();
            default -> null;
        };
        CloudWatchMeterRegistry registry = new CloudWatchMeterRegistry(
                cloudWatchConfig, Clock.SYSTEM, cloudWatchAsyncClient);
        registry.config().meterFilter(MeterFilter.denyUnless(
                meter -> meter.getName().startsWith("miriyum.search.llm.")));
        return registry;
    }

    @ConfigurationProperties("miriyum.cloudwatch-metrics")
    public record Properties(boolean enabled, String namespace, String region, Duration step) {
    }
}
