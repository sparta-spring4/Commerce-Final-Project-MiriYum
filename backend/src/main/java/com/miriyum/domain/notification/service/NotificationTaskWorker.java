package com.miriyum.domain.notification.service;

import com.miriyum.domain.notification.config.NotificationSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 유효한 Notification runtime 정책이 있을 때만 due 작업을 실행한다.
 */
@Component
@ConditionalOnProperty(
        name = "miriyum.notification.worker.enabled",
        havingValue = "true"
)
public class NotificationTaskWorker {

    private final NotificationSettings settings;
    private final NotificationDeliveryService deliveryService;

    public NotificationTaskWorker(
            NotificationSettings settings,
            NotificationDeliveryService deliveryService
    ) {
        this.settings = settings;
        this.deliveryService = deliveryService;
    }

    /**
     * 설정 스냅샷 하나로 제한된 due batch를 처리한다.
     *
     * @return 최종 상태로 수렴한 작업 수
     */
    @Scheduled(
            scheduler = "notificationTaskScheduler",
            fixedDelayString = "#{@notificationWorkerPollDelayMs}",
            initialDelayString = "#{@notificationWorkerInitialDelayMs}"
    )
    public int deliverDueBatch() {
        return settings.runtimePolicy()
                .map(deliveryService::deliverDueBatch)
                .orElse(0);
    }
}
