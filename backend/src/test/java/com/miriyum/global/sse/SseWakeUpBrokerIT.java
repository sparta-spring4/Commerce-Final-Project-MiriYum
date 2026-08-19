package com.miriyum.global.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
@Tag("integration-shard-d")
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
}
