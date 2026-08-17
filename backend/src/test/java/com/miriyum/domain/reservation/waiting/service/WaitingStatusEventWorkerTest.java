package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.miriyum.domain.notification.config.NotificationSettings;
import org.junit.jupiter.api.Test;

class WaitingStatusEventWorkerTest {

    @Test
    void invalidNotificationPolicyFailsClosedWithoutDispatching() {
        WaitingStatusEventDispatcher dispatcher = mock(WaitingStatusEventDispatcher.class);
        WaitingStatusEventWorker worker = new WaitingStatusEventWorker(
                new NotificationSettings(
                        false, null, null, null, null, null, null, null, null, null),
                dispatcher
        );

        assertThat(worker.dispatchDueBatch()).isZero();
        then(dispatcher).shouldHaveNoInteractions();
    }

    @Test
    void validNotificationPolicyLimitsTheStatusEventBatch() {
        WaitingStatusEventDispatcher dispatcher = mock(WaitingStatusEventDispatcher.class);
        given(dispatcher.dispatchNext()).willReturn(true, true, false);
        WaitingStatusEventWorker worker = new WaitingStatusEventWorker(
                new NotificationSettings(
                        true, "notification-v1", "worker-a", 3, 30_000L,
                        3, 5_000L, 60_000L, 1_000L, 1_000L),
                dispatcher
        );

        assertThat(worker.dispatchDueBatch()).isEqualTo(2);
        then(dispatcher).should(org.mockito.Mockito.times(3)).dispatchNext();
    }
}
