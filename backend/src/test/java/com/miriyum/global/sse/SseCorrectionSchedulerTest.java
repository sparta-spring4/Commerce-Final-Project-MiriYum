package com.miriyum.global.sse;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SseCorrectionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void maintenanceExpiresJwtBeforeBoundedCorrectionAndKeepalive() {
        SseStreamService streams = mock(SseStreamService.class);
        SseCorrectionScheduler scheduler = new SseCorrectionScheduler(
                settings(), streams, Clock.fixed(NOW, ZoneOffset.UTC));

        scheduler.correct();
        scheduler.keepalive();

        InOrder order = inOrder(streams);
        order.verify(streams).expireJwtConnections(NOW);
        order.verify(streams).correctBatch(20);
        order.verify(streams).expireJwtConnections(NOW);
        order.verify(streams).sendKeepalives();
    }

    @Test
    void wakeUpPublishesOnlyAfterCommitAndRedisFailureCannotEscapeCallback() {
        SseWakeUpBroker broker = mock(SseWakeUpBroker.class);
        SseAfterCommitWakeUp wakeUps = new SseAfterCommitWakeUp(broker);
        List<SseWakeUpTarget> targets =
                List.of(SseWakeUpTarget.notificationAccount(41L));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        wakeUps.afterCommit(targets);

        verify(broker, never()).publish(targets);
        TransactionSynchronization synchronization = TransactionSynchronizationManager
                .getSynchronizations().getFirst();
        org.mockito.Mockito.doThrow(new RuntimeException("valkey unavailable"))
                .when(broker).publish(targets);
        assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();
        verify(broker).publish(targets);
    }

    private static SseRuntimeProperties settings() {
        return new SseRuntimeProperties(
                true, "0123456789abcdef0123456789abcdef",
                Duration.ofMinutes(1), Duration.ofSeconds(15), Duration.ofSeconds(5),
                20, 100, 5);
    }
}
