package com.miriyum.global.sse;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** HMAC routing key만 versioned Valkey channel로 fan-out한다. */
@Component
public class SseWakeUpBroker implements MessageListener {

    public static final String CHANNEL = "miriyum:sse:wakeup:v1";
    private static final String MESSAGE_VERSION = "v1";
    private static final String ROUTING_KEY_PATTERN = "[0-9a-f]{64}";

    private final StringRedisTemplate redis;
    private final SseCursorCodec cursorCodec;
    private final SseStreamService streams;

    public SseWakeUpBroker(
            StringRedisTemplate redis,
            SseCursorCodec cursorCodec,
            SseStreamService streams
    ) {
        this.redis = redis;
        this.cursorCodec = cursorCodec;
        this.streams = streams;
    }

    public void publish(Collection<SseWakeUpTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("wake-up targets must not be empty");
        }
        Set<String> routingKeys = new LinkedHashSet<>();
        targets.forEach(target -> routingKeys.add(cursorCodec.routingKey(target)));
        routingKeys.forEach(key -> redis.convertAndSend(
                CHANNEL, MESSAGE_VERSION + "\n" + key));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        String[] fields = payload.split("\\n", -1);
        if (fields.length != 2
                || !MESSAGE_VERSION.equals(fields[0])
                || !fields[1].matches(ROUTING_KEY_PATTERN)) {
            return;
        }
        streams.refreshRoutingKey(fields[1]);
    }
}
