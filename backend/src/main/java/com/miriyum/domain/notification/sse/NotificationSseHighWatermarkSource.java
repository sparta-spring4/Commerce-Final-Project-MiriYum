package com.miriyum.domain.notification.sse;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.notification.repository.NotificationReadRepository;
import com.miriyum.global.sse.SseAudience;
import com.miriyum.global.sse.SseHighWatermarkSource;
import com.miriyum.global.sse.SseSignalState;
import com.miriyum.global.sse.SseStreamScope;
import com.miriyum.global.sse.SseWakeUpTarget;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 활성 소비자 본인의 공개 IN_APP 이력 watermark만 제공한다. */
@Component
public class NotificationSseHighWatermarkSource implements SseHighWatermarkSource {

    private final ConsumerAccountService accountService;
    private final NotificationReadRepository readRepository;

    public NotificationSseHighWatermarkSource(
            ConsumerAccountService accountService,
            NotificationReadRepository readRepository
    ) {
        this.accountService = accountService;
        this.readRepository = readRepository;
    }

    @Override
    public boolean supports(SseAudience audience) {
        return audience == SseAudience.NOTIFICATION_CONSUMER;
    }

    @Override
    public SseSignalState read(SseStreamScope scope) {
        if (!supports(scope.audience())) {
            throw new IllegalArgumentException("unsupported notification SSE audience");
        }
        accountService.requireActiveAccount(scope.accountId());
        return new SseSignalState(
                readRepository.findChangeVersion(scope.accountId()),
                Set.of(SseWakeUpTarget.notificationAccount(scope.accountId()))
        );
    }
}
