package com.miriyum.global.sse;

import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 업무 transaction 성공 뒤에만 비내구성 Valkey hint를 발행한다. */
@Component
public class SseAfterCommitWakeUp implements SseWakeUpRequester {

    private final SseWakeUpBroker broker;

    public SseAfterCommitWakeUp(SseWakeUpBroker broker) {
        this.broker = broker;
    }

    @Override
    public void afterCommit(Collection<SseWakeUpTarget> targets) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("SSE wake-up requires transaction synchronization");
        }
        List<SseWakeUpTarget> immutableTargets = List.copyOf(targets);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            broker.publish(immutableTargets);
                        } catch (RuntimeException ignored) {
                            // Valkey hint 실패는 이미 commit된 업무 상태를 되돌리지 않는다.
                        }
                    }
                });
    }
}
