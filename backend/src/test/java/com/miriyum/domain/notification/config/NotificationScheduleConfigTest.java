package com.miriyum.domain.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.notification.service.NotificationTaskWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

class NotificationScheduleConfigTest {

    @Test
    void configurationOwnsSettingsBindingAndSchedulingActivation() {
        assertThat(NotificationScheduleConfig.class)
                .hasAnnotation(EnableConfigurationProperties.class);
        assertThat(NotificationScheduleConfig.WorkerSchedulingActivation.class)
                .hasAnnotation(EnableScheduling.class)
                .hasAnnotation(ConditionalOnProperty.class);
    }

    @Test
    void workerExplicitlyUsesTheNotificationScheduler() throws NoSuchMethodException {
        Scheduled scheduled = NotificationTaskWorker.class
                .getMethod("deliverDueBatch")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.scheduler()).isEqualTo("notificationTaskScheduler");
    }
}
