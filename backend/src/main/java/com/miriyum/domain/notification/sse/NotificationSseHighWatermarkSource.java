package com.miriyum.domain.notification.sse;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.notification.repository.NotificationReadRepository;
import com.miriyum.domain.notification.repository.NotificationTaskRepository;
import com.miriyum.global.sse.SseAudience;
import com.miriyum.global.sse.SseHighWatermarkSource;
import com.miriyum.global.sse.SseSignalState;
import com.miriyum.global.sse.SseStreamScope;
import com.miriyum.global.sse.SseWakeUpTarget;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 활성 소비자 본인의 공개 IN_APP 이력 watermark만 제공한다. */
@Component
public class NotificationSseHighWatermarkSource implements SseHighWatermarkSource {

    private final ConsumerAccountService accountService;
    private final NotificationTaskRepository taskRepository;
    private final NotificationReadRepository readRepository;

    public NotificationSseHighWatermarkSource(
            ConsumerAccountService accountService,
            NotificationTaskRepository taskRepository,
            NotificationReadRepository readRepository
    ) {
        this.accountService = accountService;
        this.taskRepository = taskRepository;
        this.readRepository = readRepository;
    }

    @Override
    public boolean supports(SseAudience audience) {
        return audience == SseAudience.NOTIFICATION_CONSUMER;
    }

    @Override
    @Transactional(readOnly = true)
    public SseSignalState read(SseStreamScope scope) {
        if (!supports(scope.audience())) {
            throw new IllegalArgumentException("unsupported notification SSE audience");
        }
        accountService.requireActiveAccount(scope.accountId());
        long changeVersion = readRepository.findChangeVersion(scope.accountId());
        long legacyDeliveryWatermark =
                taskRepository.findDeliveredInAppHighWatermark(scope.accountId());
        return new SseSignalState(
                Math.max(changeVersion, legacyDeliveryWatermark),
                Set.of(SseWakeUpTarget.notificationAccount(scope.accountId()))
        );
    }
}
