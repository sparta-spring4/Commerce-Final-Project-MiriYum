package com.miriyum.global.sse;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** pre-stream 검증과 MySQL 기반 changed signal 수렴을 조정한다. */
@Service
public class SseStreamService {

    private final SseRuntimeProperties properties;
    private final SseCursorCodec cursorCodec;
    private final SseConnectionRegistry registry;
    private final List<SseHighWatermarkSource> sources;
    private final Clock clock;
    private final LongFunction<SseEmitter> emitterFactory;

    @Autowired
    public SseStreamService(
            SseRuntimeProperties properties,
            SseCursorCodec cursorCodec,
            SseConnectionRegistry registry,
            List<SseHighWatermarkSource> sources,
            Clock clock
    ) {
        this(properties, cursorCodec, registry, sources, clock, SseEmitter::new);
    }

    SseStreamService(
            SseRuntimeProperties properties,
            SseCursorCodec cursorCodec,
            SseConnectionRegistry registry,
            List<SseHighWatermarkSource> sources,
            Clock clock,
            LongFunction<SseEmitter> emitterFactory
    ) {
        this.properties = properties;
        this.cursorCodec = cursorCodec;
        this.registry = registry;
        this.sources = List.copyOf(sources);
        this.clock = clock;
        this.emitterFactory = emitterFactory;
    }

    public SseEmitter open(
            SseStreamScope scope,
            String lastEventId,
            Instant accessTokenExpiresAt
    ) {
        SseRuntimeProperties.RuntimePolicy policy = properties.requireRuntime();
        Instant now = clock.instant();
        if (accessTokenExpiresAt == null || !now.isBefore(accessTokenExpiresAt)) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        if (lastEventId != null) {
            cursorCodec.decode(scope, lastEventId);
        }
        SseHighWatermarkSource source = source(scope.audience());
        SseSignalState current = source.read(scope);
        long timeoutMillis = Math.min(
                policy.timeout().toMillis(),
                Duration.between(now, accessTokenExpiresAt).toMillis());
        if (timeoutMillis <= 0) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        SseEmitter emitter = emitterFactory.apply(timeoutMillis);
        UUID id = UUID.randomUUID();
        Set<String> routingKeys = routingKeys(current);
        SseConnection connection = new SseConnection(
                id, scope, emitter, accessTokenExpiresAt, routingKeys,
                () -> registry.remove(id));
        registry.register(connection, policy);
        emitter.onCompletion(connection::complete);
        emitter.onTimeout(connection::complete);
        emitter.onError(error -> connection.complete());
        if (!connection.sendInitial(cursorCodec, current)) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        return emitter;
    }

    public void refreshRoutingKey(String routingKey) {
        registry.findByRoutingKey(routingKey).forEach(this::refresh);
    }

    public void correctBatch(int limit) {
        registry.nextCorrectionBatch(limit).forEach(this::refresh);
    }

    public void sendKeepalives() {
        registry.all().forEach(SseConnection::sendKeepalive);
    }

    public void expireJwtConnections(Instant now) {
        registry.all().stream().filter(connection -> connection.isExpired(now))
                .forEach(SseConnection::complete);
    }

    private void refresh(SseConnection connection) {
        try {
            SseSignalState current = source(connection.scope().audience())
                    .read(connection.scope());
            registry.updateRoutingKeys(connection.id(), routingKeys(current));
            connection.sendChanged(cursorCodec, current);
        } catch (RuntimeException unavailable) {
            // MySQL/권한의 일시 실패는 연결을 성공 처리하지 않고 다음 wake-up·보정에서 재시도한다.
        }
    }

    private SseHighWatermarkSource source(SseAudience audience) {
        List<SseHighWatermarkSource> matches = sources.stream()
                .filter(candidate -> candidate.supports(audience))
                .toList();
        if (matches.size() != 1) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        return matches.getFirst();
    }

    private Set<String> routingKeys(SseSignalState state) {
        return state.wakeUpTargets().stream()
                .map(cursorCodec::routingKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
