package com.miriyum.global.sse;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

class SseWakeUpBrokerTest {

    @Test
    void rejectsWrongVersionAndMalformedRoutingKeysWithoutRefreshingStreams() {
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

    private static Message message(String payload) {
        Message message = mock(Message.class);
        given(message.getBody()).willReturn(payload.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    private static SseRuntimeProperties settings() {
        return new SseRuntimeProperties(
                true, "0123456789abcdef0123456789abcdef",
                Duration.ofMinutes(1), Duration.ofSeconds(15), Duration.ofSeconds(5),
                20, 100, 5);
    }
}
