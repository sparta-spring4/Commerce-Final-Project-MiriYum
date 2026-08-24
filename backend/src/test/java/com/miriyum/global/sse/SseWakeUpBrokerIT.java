package com.miriyum.global.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-a")
class SseWakeUpBrokerIT {

    private static final String PASSWORD = "test-valkey-password";

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(
            DockerImageName.parse("valkey/valkey:8.1-alpine"))
            .withExposedPorts(6379)
            .withCommand("valkey-server", "--requirepass", PASSWORD);

    private LettuceConnectionFactory factoryA;
    private LettuceConnectionFactory factoryB;
    private RedisMessageListenerContainer listenerA;
    private RedisMessageListenerContainer listenerB;

    @BeforeEach
    void setUp() {
        factoryA = connectionFactory();
        factoryB = connectionFactory();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (listenerA != null) {
            listenerA.destroy();
        }
        if (listenerB != null) {
            listenerB.destroy();
        }
        factoryA.destroy();
        factoryB.destroy();
    }

    @Test
    void oneInstancePublishWakesAnotherInstanceWithOnlyOpaqueRoutingKey() {
        SseRuntimeProperties properties = settings();
        SseCursorCodec codecA = new SseCursorCodec(properties);
        SseCursorCodec codecB = new SseCursorCodec(properties);
        SseStreamService streamsA = mock(SseStreamService.class);
        SseStreamService streamsB = mock(SseStreamService.class);
        SseWakeUpBroker brokerA = new SseWakeUpBroker(
                redis(factoryA), codecA, streamsA);
        SseWakeUpBroker brokerB = new SseWakeUpBroker(
                redis(factoryB), codecB, streamsB);
        listenerA = listener(factoryA, brokerA);
        listenerB = listener(factoryB, brokerB);
        String expectedRoutingKey = codecA.routingKey(
                SseWakeUpTarget.notificationAccount(41L));

        brokerA.publish(List.of(SseWakeUpTarget.notificationAccount(41L)));

        verify(streamsB, timeout(5_000)).refreshRoutingKey(expectedRoutingKey);
        assertThat(expectedRoutingKey).matches("^[0-9a-f]{64}$").doesNotContain("41");
    }

    @Test
    void duplicateFanOutIsCollapsedAndCorrectionRecoversPublishLostWhileSubscribedStopped()
            throws Exception {
        SseRuntimeProperties properties = settings();
        SseCursorCodec codecA = new SseCursorCodec(properties);
        SseCursorCodec codecB = new SseCursorCodec(properties);
        TrackingSource source = new TrackingSource(new SseSignalState(
                1L, Set.of(SseWakeUpTarget.notificationAccount(41L))));
        RecordingEmitter emitter = new RecordingEmitter();
        SseConnectionRegistry registry = new SseConnectionRegistry();
        SseStreamService streamsB = new SseStreamService(
                properties,
                codecB,
                registry,
                List.of(source),
                Clock.fixed(Instant.parse("2026-08-19T01:00:00Z"), ZoneOffset.UTC),
                timeout -> emitter);
        SseWakeUpBroker brokerA = new SseWakeUpBroker(
                redis(factoryA), codecA, mock(SseStreamService.class));
        SseWakeUpBroker brokerB = new SseWakeUpBroker(redis(factoryB), codecB, streamsB);
        listenerA = listener(factoryA, brokerA);
        listenerB = listener(factoryB, brokerB);
        streamsB.open(
                SseStreamScope.notificationConsumer(41L),
                null,
                Instant.parse("2026-08-19T01:01:00Z"));
        source.state = new SseSignalState(
                2L, Set.of(SseWakeUpTarget.notificationAccount(41L)));
        source.expectReads(2);

        brokerA.publish(List.of(SseWakeUpTarget.notificationAccount(41L)));
        brokerA.publish(List.of(SseWakeUpTarget.notificationAccount(41L)));

        assertThat(source.awaitExpectedReads()).isTrue();
        assertThat(emitter.frames).hasSize(2);

        listenerB.stop();
        int readsBeforeLostPublish = source.readCount.get();
        source.state = new SseSignalState(
                3L, Set.of(SseWakeUpTarget.notificationAccount(41L)));
        brokerA.publish(List.of(SseWakeUpTarget.notificationAccount(41L)));
        assertThat(source.readCount).hasValue(readsBeforeLostPublish);

        streamsB.correctBatch(10);

        assertThat(emitter.frames).hasSize(3);
        assertThat(registry.count()).isOne();
    }

    @Test
    void wrongVersionAndMalformedRoutingKeysDoNotRefreshStreams() {
        SseStreamService streams = mock(SseStreamService.class);
        SseWakeUpBroker broker = new SseWakeUpBroker(
                mock(StringRedisTemplate.class),
                new SseCursorCodec(settings()),
                streams);

        broker.onMessage(message("v2\n" + "a".repeat(64)), null);
        broker.onMessage(message("v1\nconsumer:41"), null);
        broker.onMessage(message("v1\n" + "a".repeat(64) + "\nextra"), null);

        verifyNoInteractions(streams);
    }

    private LettuceConnectionFactory connectionFactory() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                VALKEY.getHost(), VALKEY.getMappedPort(6379));
        configuration.setPassword(PASSWORD);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        return factory;
    }

    private static StringRedisTemplate redis(LettuceConnectionFactory factory) {
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        return redis;
    }

    private static RedisMessageListenerContainer listener(
            LettuceConnectionFactory factory,
            SseWakeUpBroker broker
    ) {
        RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(factory);
        listener.addMessageListener(broker, new ChannelTopic(SseWakeUpBroker.CHANNEL));
        listener.afterPropertiesSet();
        listener.start();
        return listener;
    }

    private static SseRuntimeProperties settings() {
        return new SseRuntimeProperties(
                true, "0123456789abcdef0123456789abcdef",
                Duration.ofMinutes(1), Duration.ofSeconds(15), Duration.ofSeconds(5),
                20, 100, 5);
    }

    private static Message message(String payload) {
        Message message = mock(Message.class);
        given(message.getBody()).willReturn(payload.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    private static final class TrackingSource implements SseHighWatermarkSource {
        private volatile SseSignalState state;
        private final AtomicInteger readCount = new AtomicInteger();
        private final AtomicReference<CountDownLatch> expectedReads = new AtomicReference<>();

        private TrackingSource(SseSignalState state) {
            this.state = state;
        }

        private void expectReads(int count) {
            expectedReads.set(new CountDownLatch(count));
        }

        private boolean awaitExpectedReads() throws InterruptedException {
            return expectedReads.get().await(5, TimeUnit.SECONDS);
        }

        @Override
        public boolean supports(SseAudience audience) {
            return audience == SseAudience.NOTIFICATION_CONSUMER;
        }

        @Override
        public SseSignalState read(SseStreamScope scope) {
            readCount.incrementAndGet();
            CountDownLatch latch = expectedReads.get();
            if (latch != null) {
                latch.countDown();
            }
            return state;
        }
    }

    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> frames = java.util.Collections.synchronizedList(
                new ArrayList<>());

        private RecordingEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            StringBuilder frame = new StringBuilder();
            builder.build().forEach(part -> frame.append(part.getData()));
            frames.add(frame.toString());
        }
    }
}
