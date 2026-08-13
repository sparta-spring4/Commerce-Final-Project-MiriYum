package com.miriyum.domain.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

class NotificationScheduleConfigTest {

    @Test
    void configurationOwnsSettingsBindingAndSchedulingActivation() {
        assertThat(NotificationScheduleConfig.class)
                .hasAnnotation(EnableConfigurationProperties.class);
        assertThat(NotificationScheduleConfig.WorkerSchedulingActivation.class)
                .hasAnnotation(EnableScheduling.class)
                .hasAnnotation(ConditionalOnProperty.class);
    }
}
