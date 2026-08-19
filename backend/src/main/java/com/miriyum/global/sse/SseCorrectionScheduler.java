package com.miriyum.global.sse;

import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 로컬 연결만 bounded batch로 재조회하고 keepalive·JWT 만료를 정리한다. */
@Component
@ConditionalOnProperty(name = "miriyum.sse.enabled", havingValue = "true")
public class SseCorrectionScheduler {

    private final SseRuntimeProperties properties;
    private final SseStreamService streams;
    private final Clock clock;

    public SseCorrectionScheduler(
            SseRuntimeProperties properties,
            SseStreamService streams,
            Clock clock
    ) {
        this.properties = properties;
        this.streams = streams;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "#{@sseCorrectionDelayMs}",
            scheduler = "sseTaskScheduler")
    void correct() {
        SseRuntimeProperties.RuntimePolicy policy = policyOrNull();
        if (policy == null) {
            return;
        }
        streams.expireJwtConnections(clock.instant());
        streams.correctBatch(policy.correctionBatchSize());
    }

    @Scheduled(
            fixedDelayString = "#{@sseHeartbeatDelayMs}",
            scheduler = "sseTaskScheduler")
    void keepalive() {
        if (policyOrNull() == null) {
            return;
        }
        streams.expireJwtConnections(clock.instant());
        streams.sendKeepalives();
    }

    private SseRuntimeProperties.RuntimePolicy policyOrNull() {
        try {
            return properties.requireRuntime();
        } catch (ServiceException unavailable) {
            return null;
        }
    }
}
