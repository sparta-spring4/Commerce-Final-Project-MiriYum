package com.miriyum.global.sse;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 단일 emitter의 전송 순서, watermark와 lifecycle cleanup을 소유한다. */
final class SseConnection {

    private final UUID id;
    private final SseStreamScope scope;
    private final SseEmitter emitter;
    private final Instant expiresAt;
    private final Runnable cleanup;
    private final ReentrantLock sendLock = new ReentrantLock();
    private final AtomicBoolean completed = new AtomicBoolean();
    private volatile Set<String> routingKeys;
    private long lastSentWatermark = -1L;

    SseConnection(
            UUID id,
            SseStreamScope scope,
            SseEmitter emitter,
            Instant expiresAt,
            Set<String> routingKeys,
            Runnable cleanup
    ) {
        if (id == null || scope == null || emitter == null || expiresAt == null
                || routingKeys == null || routingKeys.isEmpty() || cleanup == null) {
            throw new IllegalArgumentException("SSE connection fields are required");
        }
        this.id = id;
        this.scope = scope;
        this.emitter = emitter;
        this.expiresAt = expiresAt;
        this.routingKeys = Set.copyOf(routingKeys);
        this.cleanup = cleanup;
    }

    UUID id() {
        return id;
    }

    SseStreamScope scope() {
        return scope;
    }

    long accountId() {
        return scope.accountId();
    }

    Set<String> routingKeys() {
        return routingKeys;
    }

    void replaceRoutingKeys(Set<String> next) {
        if (next == null || next.isEmpty()) {
            throw new IllegalArgumentException("routing keys must not be empty");
        }
        routingKeys = Set.copyOf(next);
    }

    boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    boolean sendInitial(SseCursorCodec codec, SseSignalState state) {
        sendLock.lock();
        try {
            return sendFrame(codec, state.watermark());
        } finally {
            sendLock.unlock();
        }
    }

    boolean sendChanged(SseCursorCodec codec, SseSignalState state) {
        sendLock.lock();
        try {
            if (completed.get() || state.watermark() <= lastSentWatermark) {
                return true;
            }
            return sendFrame(codec, state.watermark());
        } finally {
            sendLock.unlock();
        }
    }

    boolean sendKeepalive() {
        sendLock.lock();
        try {
            if (completed.get()) {
                return false;
            }
            emitter.send(SseEmitter.event().comment("keepalive"));
            return true;
        } catch (IOException | IllegalStateException sendFailure) {
            fail(sendFailure);
            return false;
        } finally {
            sendLock.unlock();
        }
    }

    void complete() {
        if (completed.compareAndSet(false, true)) {
            cleanup.run();
        }
    }

    private boolean sendFrame(SseCursorCodec codec, long watermark) {
        if (completed.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event()
                    .id(codec.encode(scope, watermark))
                    .name(scope.audience().eventName())
                    .data(Map.of()));
            lastSentWatermark = watermark;
            return true;
        } catch (IOException | IllegalStateException sendFailure) {
            fail(sendFailure);
            return false;
        }
    }

    private void fail(Throwable failure) {
        try {
            emitter.completeWithError(failure);
        } finally {
            complete();
        }
    }
}
