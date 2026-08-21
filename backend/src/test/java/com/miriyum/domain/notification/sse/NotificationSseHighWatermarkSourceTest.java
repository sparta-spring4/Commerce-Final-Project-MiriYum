package com.miriyum.domain.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.notification.repository.NotificationReadRepository;
import com.miriyum.domain.notification.repository.NotificationTaskRepository;
import com.miriyum.global.sse.SseAudience;
import com.miriyum.global.sse.SseSignalState;
import com.miriyum.global.sse.SseStreamScope;
import com.miriyum.global.sse.SseWakeUpTarget;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class NotificationSseHighWatermarkSourceTest {

    @Test
    void returnsTheGreaterOfAccountChangeVersionAndLegacyPublicHistoryWatermark() {
        ConsumerAccountService accounts = mock(ConsumerAccountService.class);
        NotificationTaskRepository tasks = mock(NotificationTaskRepository.class);
        NotificationReadRepository reads = mock(NotificationReadRepository.class);
        given(tasks.findDeliveredInAppHighWatermark(41L)).willReturn(109L);
        given(reads.findChangeVersion(41L)).willReturn(113L);
        NotificationSseHighWatermarkSource source =
                new NotificationSseHighWatermarkSource(accounts, tasks, reads);

        SseSignalState state = source.read(SseStreamScope.notificationConsumer(41L));

        assertThat(source.supports(SseAudience.NOTIFICATION_CONSUMER)).isTrue();
        assertThat(state.watermark()).isEqualTo(113L);
        assertThat(state.wakeUpTargets())
                .containsExactly(SseWakeUpTarget.notificationAccount(41L));
        InOrder order = inOrder(accounts, reads, tasks);
        order.verify(accounts).requireActiveAccount(41L);
        order.verify(reads).findChangeVersion(41L);
        order.verify(tasks).findDeliveredInAppHighWatermark(41L);
    }
}
