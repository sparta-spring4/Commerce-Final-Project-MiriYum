package com.miriyum.global.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseStreamServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");

    @Test
    void opensWithJwtBoundTimeoutAndSendsCurrentMysqlSignal() {
        MutableSource source = new MutableSource(new SseSignalState(
                11L, Set.of(SseWakeUpTarget.notificationAccount(41L))));
        RecordingFactory emitters = new RecordingFactory();
        Fixture fixture = fixture(source, emitters, 100, 5);

        fixture.service.open(
                SseStreamScope.notificationConsumer(41L), null, NOW.plusSeconds(30));

        assertThat(emitters.timeoutMillis).isEqualTo(30_000L);
        assertThat(emitters.emitter.frames).singleElement()
                .satisfies(frame -> assertThat(frame)
                        .contains("event:notifications.changed", "data:{}", "id:"));
        assertThat(fixture.registry.count()).isOne();
    }

    @Test
    void validatesReconnectScopeBeforeReadingMysql() {
        MutableSource source = new MutableSource(new SseSignalState(
                11L, Set.of(SseWakeUpTarget.notificationAccount(41L))));
        Fixture fixture = fixture(source, new RecordingFactory(), 100, 5);
        String anotherAccountCursor = fixture.codec.encode(
                SseStreamScope.notificationConsumer(42L), 9L);

        assertThatThrownBy(() -> fixture.service.open(
                SseStreamScope.notificationConsumer(41L),
                anotherAccountCursor,
                NOW.plusSeconds(30)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        assertThat(source.readCount).isZero();
        assertThat(fixture.registry.count()).isZero();
    }

    @Test
    void wakeUpsSendOnlyStrictlyNewerWatermarksAndRefreshRoutingKeys() {
        MutableSource source = new MutableSource(new SseSignalState(
                11L, Set.of(SseWakeUpTarget.notificationAccount(41L))));
        RecordingFactory emitters = new RecordingFactory();
        Fixture fixture = fixture(source, emitters, 100, 5);
        fixture.service.open(
                SseStreamScope.notificationConsumer(41L), null, NOW.plusSeconds(30));
        String route = fixture.codec.routingKey(SseWakeUpTarget.notificationAccount(41L));

        fixture.service.refreshRoutingKey(route);
        source.state = new SseSignalState(
                12L, Set.of(SseWakeUpTarget.notificationAccount(41L)));
        fixture.service.refreshRoutingKey(route);
        fixture.service.refreshRoutingKey(route);

        assertThat(emitters.emitter.frames).hasSize(2);
        assertThat(emitters.emitter.frames.get(1)).contains("event:notifications.changed");
    }

    @Test
    void oneWakeUpReadsMysqlOnceAndFansOutToEveryConnectionInTheSameScope() {
        MutableSource source = new MutableSource(new SseSignalState(
                11L, Set.of(SseWakeUpTarget.notificationAccount(41L))));
        RecordingEmitter first = new RecordingEmitter(60_000L);
        RecordingEmitter second = new RecordingEmitter(60_000L);
        AtomicInteger emitterIndex = new AtomicInteger();
        Fixture fixture = fixture(
                source,
                timeout -> emitterIndex.getAndIncrement() == 0 ? first : second,
                100,
                5);
        SseStreamScope scope = SseStreamScope.notificationConsumer(41L);
        fixture.service.open(scope, null, NOW.plusSeconds(30));
        fixture.service.open(scope, null, NOW.plusSeconds(30));
        int readsBeforeWakeUp = source.readCount;
        source.state = new SseSignalState(
                12L, Set.of(SseWakeUpTarget.notificationAccount(41L)));

        fixture.service.refreshRoutingKey(
                fixture.codec.routingKey(SseWakeUpTarget.notificationAccount(41L)));

        assertThat(source.readCount - readsBeforeWakeUp).isOne();
        assertThat(first.frames).hasSize(2);
        assertThat(second.frames).hasSize(2);
    }

    @Test
    void staleRefreshCannotReplaceRoutingKeysAfterANewerRefresh() throws Exception {
        InterleavingSource source = new InterleavingSource();
        RecordingFactory emitters = new RecordingFactory();
        Fixture fixture = fixture(source, emitters, 100, 5);
        SseStreamScope scope = SseStreamScope.notificationConsumer(41L);
        fixture.service.open(scope, null, NOW.plusSeconds(30));
        String oldRoute = fixture.codec.routingKey(
                SseWakeUpTarget.notificationAccount(41L));
        String newRoute = fixture.codec.routingKey(
                SseWakeUpTarget.notificationAccount(99L));
        source.startInterleaving();

        Thread stale = new Thread(() -> fixture.service.refreshRoutingKey(oldRoute));
        Thread fresh = new Thread(() -> fixture.service.refreshRoutingKey(oldRoute));
        stale.start();
        assertThat(source.staleReadEntered.await(2, TimeUnit.SECONDS)).isTrue();
        fresh.start();
        assertThat(source.freshReadEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
        source.releaseStaleRead.countDown();
        stale.join(2_000);
        fresh.join(2_000);

        assertThat(stale.isAlive()).isFalse();
        assertThat(fresh.isAlive()).isFalse();
        assertThat(fixture.registry.findByRoutingKey(oldRoute)).isEmpty();
        assertThat(fixture.registry.findByRoutingKey(newRoute))
                .extracting(SseConnection::scope)
                .containsExactly(scope);
    }

    @Test
    void concurrentSendsAreSerializedPerConnection() throws Exception {
        BlockingEmitter emitter = new BlockingEmitter();
        MutableSource source = new MutableSource(new SseSignalState(
                1L, Set.of(SseWakeUpTarget.notificationAccount(41L))));
        Fixture fixture = fixture(
                source,
                timeout -> emitter,
                100,
                5);
        fixture.service.open(
                SseStreamScope.notificationConsumer(41L), null, NOW.plusSeconds(30));
        String route = fixture.codec.routingKey(SseWakeUpTarget.notificationAccount(41L));
        source.state = new SseSignalState(
                2L, Set.of(SseWakeUpTarget.notificationAccount(41L)));

        Thread first = new Thread(() -> fixture.service.refreshRoutingKey(route));
        Thread second = new Thread(() -> fixture.service.refreshRoutingKey(route));
        first.start();
        assertThat(emitter.entered.await(2, TimeUnit.SECONDS)).isTrue();
        second.start();
        emitter.release.countDown();
        first.join(2_000);
        second.join(2_000);

        assertThat(emitter.maximumConcurrentSends.get()).isOne();
    }

    @Test
    void completionTimeoutErrorAndJwtExpiryEachRemoveOnlyTheirConnection() {
        MutableSource source = new MutableSource(new SseSignalState(
                1L, Set.of(SseWakeUpTarget.notificationAccount(41L))));
        CallbackEmitter completed = new CallbackEmitter();
        CallbackEmitter timedOut = new CallbackEmitter();
        CallbackEmitter errored = new CallbackEmitter();
        CallbackEmitter jwtExpired = new CallbackEmitter();
        Queue<CallbackEmitter> emitters = new ArrayDeque<>(
                List.of(completed, timedOut, errored, jwtExpired));
        Fixture fixture = fixture(source, timeout -> emitters.remove(), 10, 10);

        for (int index = 0; index < 4; index++) {
            fixture.service.open(
                    SseStreamScope.notificationConsumer(41L),
                    null,
                    NOW.plusSeconds(30));
        }

        completed.triggerCompletion();
        timedOut.triggerTimeout();
        errored.triggerError();
        assertThat(fixture.registry.count()).isOne();

        fixture.service.expireJwtConnections(NOW.plusSeconds(30));

        assertThat(fixture.registry.count()).isZero();
    }

    @Test
    void sendErrorCompletesAndRemovesTheFailedConnection() {
        MutableSource source = new MutableSource(new SseSignalState(
                1L, Set.of(SseWakeUpTarget.notificationAccount(41L))));
        FailingAfterInitialEmitter emitter = new FailingAfterInitialEmitter();
        Fixture fixture = fixture(source, timeout -> emitter, 10, 10);
        fixture.service.open(
                SseStreamScope.notificationConsumer(41L), null, NOW.plusSeconds(30));
        source.state = new SseSignalState(
                2L, Set.of(SseWakeUpTarget.notificationAccount(41L)));

        fixture.service.refreshRoutingKey(
                fixture.codec.routingKey(SseWakeUpTarget.notificationAccount(41L)));

        assertThat(fixture.registry.count()).isZero();
    }

    private static Fixture fixture(
            SseHighWatermarkSource source,
            LongFunction<SseEmitter> emitterFactory,
            int total,
            int perAccount
    ) {
        SseRuntimeProperties properties = new SseRuntimeProperties(
                true, "0123456789abcdef0123456789abcdef",
                Duration.ofMinutes(1), Duration.ofSeconds(15), Duration.ofSeconds(5),
                10, total, perAccount);
        SseCursorCodec codec = new SseCursorCodec(properties);
        SseConnectionRegistry registry = new SseConnectionRegistry();
        SseStreamService service = new SseStreamService(
                properties,
                codec,
                registry,
                List.of(source),
                Clock.fixed(NOW, ZoneOffset.UTC),
                emitterFactory);
        return new Fixture(service, codec, registry, source);
    }

    private record Fixture(
            SseStreamService service,
            SseCursorCodec codec,
            SseConnectionRegistry registry,
            SseHighWatermarkSource source
    ) {
    }

    private static final class InterleavingSource implements SseHighWatermarkSource {
        private final AtomicInteger refreshReads = new AtomicInteger();
        private final AtomicReference<Boolean> interleaving = new AtomicReference<>(false);
        private final CountDownLatch staleReadEntered = new CountDownLatch(1);
        private final CountDownLatch freshReadEntered = new CountDownLatch(1);
        private final CountDownLatch releaseStaleRead = new CountDownLatch(1);

        private void startInterleaving() {
            interleaving.set(true);
        }

        @Override
        public boolean supports(SseAudience audience) {
            return audience == SseAudience.NOTIFICATION_CONSUMER;
        }

        @Override
        public SseSignalState read(SseStreamScope scope) {
            if (!interleaving.get()) {
                return new SseSignalState(
                        1L, Set.of(SseWakeUpTarget.notificationAccount(41L)));
            }
            if (refreshReads.getAndIncrement() == 0) {
                staleReadEntered.countDown();
                try {
                    releaseStaleRead.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return new SseSignalState(
                        2L, Set.of(SseWakeUpTarget.notificationAccount(41L)));
            }
            freshReadEntered.countDown();
            return new SseSignalState(
                    3L, Set.of(SseWakeUpTarget.notificationAccount(99L)));
        }
    }

    private static final class MutableSource implements SseHighWatermarkSource {
        private SseSignalState state;
        private int readCount;

        private MutableSource(SseSignalState state) {
            this.state = state;
        }

        @Override
        public boolean supports(SseAudience audience) {
            return audience == SseAudience.NOTIFICATION_CONSUMER;
        }

        @Override
        public SseSignalState read(SseStreamScope scope) {
            readCount++;
            return state;
        }
    }

    private static final class RecordingFactory implements LongFunction<SseEmitter> {
        private long timeoutMillis;
        private RecordingEmitter emitter;

        @Override
        public SseEmitter apply(long timeout) {
            timeoutMillis = timeout;
            emitter = new RecordingEmitter(timeout);
            return emitter;
        }
    }

    private static class RecordingEmitter extends SseEmitter {
        private final List<String> frames = new ArrayList<>();

        private RecordingEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            StringBuilder frame = new StringBuilder();
            builder.build().forEach(part -> frame.append(part.getData()));
            frames.add(frame.toString());
        }
    }

    private static final class CallbackEmitter extends RecordingEmitter {
        private Runnable completionCallback;
        private Runnable timeoutCallback;
        private Consumer<Throwable> errorCallback;

        private CallbackEmitter() {
            super(60_000L);
        }

        @Override
        public void onCompletion(Runnable callback) {
            completionCallback = callback;
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeoutCallback = callback;
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            errorCallback = callback;
        }

        private void triggerCompletion() {
            completionCallback.run();
        }

        private void triggerTimeout() {
            timeoutCallback.run();
        }

        private void triggerError() {
            errorCallback.accept(new IOException("client disconnected"));
        }
    }

    private static final class FailingAfterInitialEmitter extends RecordingEmitter {
        private int sendCount;

        private FailingAfterInitialEmitter() {
            super(60_000L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            if (sendCount++ > 0) {
                throw new IOException("client disconnected");
            }
            super.send(builder);
        }
    }

    private static final class BlockingEmitter extends RecordingEmitter {
        private final AtomicInteger concurrentSends = new AtomicInteger();
        private final AtomicInteger maximumConcurrentSends = new AtomicInteger();
        private final AtomicInteger sendCount = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (sendCount.getAndIncrement() == 0) {
                super.send(builder);
                return;
            }
            int concurrent = concurrentSends.incrementAndGet();
            maximumConcurrentSends.accumulateAndGet(concurrent, Math::max);
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
                super.send(builder);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            } finally {
                concurrentSends.decrementAndGet();
            }
        }
    }
}
