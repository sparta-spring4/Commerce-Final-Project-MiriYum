package com.miriyum.domain.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.notification.repository.NotificationReadRepository;
import com.miriyum.global.sse.SseAudience;
import com.miriyum.global.sse.SseSignalState;
import com.miriyum.global.sse.SseStreamScope;
import com.miriyum.global.sse.SseWakeUpTarget;
import org.junit.jupiter.api.Test;

class NotificationSseHighWatermarkSourceTest {

    @Test
    void returnsTheAccountChangeVersionAfterReadRoutesAreActivated() {
        ConsumerAccountService accounts = mock(ConsumerAccountService.class);
        NotificationReadRepository reads = mock(NotificationReadRepository.class);
        given(reads.findChangeVersion(41L)).willReturn(113L);
        NotificationSseHighWatermarkSource source =
                new NotificationSseHighWatermarkSource(accounts, reads);

        SseSignalState state = source.read(SseStreamScope.notificationConsumer(41L));

        assertThat(source.supports(SseAudience.NOTIFICATION_CONSUMER)).isTrue();
        assertThat(state.watermark()).isEqualTo(113L);
        assertThat(state.wakeUpTargets())
                .containsExactly(SseWakeUpTarget.notificationAccount(41L));
        verify(accounts).requireActiveAccount(41L);
        verify(reads).findChangeVersion(41L);
    }
}
