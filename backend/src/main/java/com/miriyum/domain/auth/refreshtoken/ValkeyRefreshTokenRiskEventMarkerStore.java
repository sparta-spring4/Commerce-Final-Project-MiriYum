package com.miriyum.domain.auth.refreshtoken;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Valkey pending marker를 읽고 MySQL 전달 성공 뒤에 제거한다. */
@Component
public class ValkeyRefreshTokenRiskEventMarkerStore {

    private static final RedisScript<Long> DELETE_IF_UNCHANGED_SCRIPT = new DefaultRedisScript<>("""
            local occurrenceCount = redis.call('HGET', KEYS[1], 'occurrenceCount')
            if occurrenceCount == false or occurrenceCount ~= ARGV[1] then
                return 0
            end
            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[2], KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public ValkeyRefreshTokenRiskEventMarkerStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public List<PendingRefreshTokenRiskEvent> findPendingEvents() {
        try {
            var markerKeys = redisTemplate.opsForSet().members(RefreshTokenRiskEventKey.pendingIndex());
            List<PendingRefreshTokenRiskEvent> events = new ArrayList<>();
            for (String eventKey : markerKeys) {
                Map<String, String> values = redisTemplate.<String, String>opsForHash().entries(eventKey);
                if (!values.isEmpty()) {
                    events.add(toPendingEvent(eventKey, values));
                } else {
                    redisTemplate.opsForSet().remove(RefreshTokenRiskEventKey.pendingIndex(), eventKey);
                }
            }
            return events;
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    public boolean deleteIfUnchanged(String eventKey, long occurrenceCount) {
        try {
            Long deleted = redisTemplate.execute(
                    DELETE_IF_UNCHANGED_SCRIPT,
                    List.of(eventKey, RefreshTokenRiskEventKey.pendingIndex()),
                    Long.toString(occurrenceCount));
            return deleted != null && deleted > 0;
        } catch (DataAccessException | IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    private PendingRefreshTokenRiskEvent toPendingEvent(String eventKey, Map<String, String> values) {
        return new PendingRefreshTokenRiskEvent(
                eventKey,
                TokenNamespace.fromValue(required(values, "namespace")),
                Long.valueOf(required(values, "accountId")),
                required(values, "familyId"),
                required(values, "tokenHash"),
                required(values, "sourceEvent"),
                required(values, "originEvent"),
                required(values, "policyVersion"),
                Instant.ofEpochSecond(Long.parseLong(required(values, "occurredAt"))),
                Long.parseLong(required(values, "occurrenceCount")),
                Instant.ofEpochSecond(Long.parseLong(required(values, "lastOccurredAt"))));
    }

    private String required(Map<String, String> values, String field) {
        String value = values.get(field);
        if (value == null || value.isBlank()) {
            throw unavailable();
        }
        return value;
    }

    private ServiceException unavailable() {
        return new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
    }
}
