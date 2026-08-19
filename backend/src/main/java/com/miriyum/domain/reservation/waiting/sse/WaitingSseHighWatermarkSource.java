package com.miriyum.domain.reservation.waiting.sse;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingStatusEventRepository.ActiveConsumerSseHighWatermark;
import com.miriyum.domain.reservation.waiting.service.WaitingStoreAuthorityPort;
import com.miriyum.global.sse.SseAudience;
import com.miriyum.global.sse.SseHighWatermarkSource;
import com.miriyum.global.sse.SseSignalState;
import com.miriyum.global.sse.SseStreamScope;
import com.miriyum.global.sse.SseWakeUpTarget;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 소비자 teamsAhead와 매장 운영자 공개 원장에 영향을 주는 Waiting watermark를 제공한다. */
@Component
public class WaitingSseHighWatermarkSource implements SseHighWatermarkSource {

    private final ConsumerAccountService accountService;
    private final WaitingStatusEventRepository eventRepository;
    private final WaitingStoreAuthorityPort authorityPort;

    public WaitingSseHighWatermarkSource(
            ConsumerAccountService accountService,
            WaitingStatusEventRepository eventRepository,
            WaitingStoreAuthorityPort authorityPort
    ) {
        this.accountService = accountService;
        this.eventRepository = eventRepository;
        this.authorityPort = authorityPort;
    }

    @Override
    public boolean supports(SseAudience audience) {
        return audience == SseAudience.WAITING_CONSUMER
                || audience == SseAudience.WAITING_STORE_OPERATOR;
    }

    @Override
    public SseSignalState read(SseStreamScope scope) {
        return switch (scope.audience()) {
            case WAITING_CONSUMER -> readConsumer(scope.accountId());
            case WAITING_STORE_OPERATOR -> readStoreOperator(
                    scope.accountId(), scope.storeId());
            default -> throw new IllegalArgumentException("unsupported Waiting SSE audience");
        };
    }

    private SseSignalState readConsumer(long accountId) {
        accountService.requireActiveAccount(accountId);
        var active = eventRepository.findActiveConsumerSseHighWatermark(accountId);
        if (active.isEmpty()) {
            return new SseSignalState(
                    eventRepository.findLatestOwnedConsumerSseHighWatermark(accountId),
                    Set.of(SseWakeUpTarget.waitingAccount(accountId))
            );
        }
        ActiveConsumerSseHighWatermark state = active.orElseThrow();
        LinkedHashSet<SseWakeUpTarget> targets = new LinkedHashSet<>();
        targets.add(SseWakeUpTarget.waitingAccount(accountId));
        targets.add(SseWakeUpTarget.waitingStoreDate(
                state.getStoreId(), state.getBusinessDate()));
        return new SseSignalState(value(state.getWatermark()), targets);
    }

    private SseSignalState readStoreOperator(long accountId, long storeId) {
        authorityPort.requireRead(accountId, storeId);
        return new SseSignalState(
                eventRepository.findStoreSseHighWatermark(storeId),
                Set.of(SseWakeUpTarget.waitingStore(storeId))
        );
    }

    private static long value(Long watermark) {
        return watermark == null ? 0L : watermark;
    }
}
