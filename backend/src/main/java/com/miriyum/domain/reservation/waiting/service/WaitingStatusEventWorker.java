package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.notification.config.NotificationSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 기존 Notification runtime 정책으로 Waiting 상태 사건을 제한 batch 처리한다. */
@Component
@ConditionalOnProperty(
        name = "miriyum.notification.worker.enabled",
        havingValue = "true"
)
public class WaitingStatusEventWorker {

    private final NotificationSettings settings;
    private final WaitingStatusEventDispatcher dispatcher;

    public WaitingStatusEventWorker(
            NotificationSettings settings,
            WaitingStatusEventDispatcher dispatcher
    ) {
        this.settings = settings;
        this.dispatcher = dispatcher;
    }

    @Scheduled(
            scheduler = "notificationTaskScheduler",
            fixedDelayString = "#{@notificationWorkerPollDelayMs}",
            initialDelayString = "#{@notificationWorkerInitialDelayMs}"
    )
    public int dispatchDueBatch() {
        return settings.runtimePolicy().map(policy -> {
            int dispatched = 0;
            for (int index = 0; index < policy.batchSize(); index++) {
                if (!dispatcher.dispatchNext()) {
                    break;
                }
                dispatched++;
            }
            return dispatched;
        }).orElse(0);
    }
}
