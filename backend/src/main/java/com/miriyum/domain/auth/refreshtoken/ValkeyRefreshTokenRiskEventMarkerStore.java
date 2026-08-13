package com.miriyum.domain.auth.refreshtoken;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Valkey pending marker를 읽고 MySQL 전달 성공 뒤에 제거한다. */
@Component
public class ValkeyRefreshTokenRiskEventMarkerStore {

    private static final long PENDING_EVENT_SCAN_COUNT = 100L;

    private static final RedisScript<Long> DELETE_IF_UNCHANGED_SCRIPT = new DefaultRedisScript<>("""
            local occurrenceCount = redis.call('HGET', KEYS[1], 'occurrenceCount')
            if occurrenceCount == false or occurrenceCount ~= ARGV[1] then
                return 0
            end
            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[2], KEYS[1])
            return 1
            """, Long.class);

    private static final RedisScript<Long> REMOVE_STALE_INDEX_MEMBER_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return 0
            end
            return redis.call('SREM', KEYS[2], KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public ValkeyRefreshTokenRiskEventMarkerStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public List<PendingRefreshTokenRiskEvent> findPendingEvents() {
        try {
            List<PendingRefreshTokenRiskEvent> events = new ArrayList<>();
            try (Cursor<String> markerKeys = redisTemplate.opsForSet().scan(
                    RefreshTokenRiskEventKey.pendingIndex(),
                    ScanOptions.scanOptions().count(PENDING_EVENT_SCAN_COUNT).build())) {
                long inspectedMarkerCount = 0;
                while (markerKeys.hasNext() && inspectedMarkerCount < PENDING_EVENT_SCAN_COUNT) {
                    String eventKey = markerKeys.next();
                    inspectedMarkerCount++;
                    Map<String, String> values = redisTemplate.<String, String>opsForHash().entries(eventKey);
                    if (!values.isEmpty()) {
                        events.add(toPendingEvent(eventKey, values));
                    } else {
                        removeFromPendingIndexIfMarkerMissing(eventKey);
                    }
                }
            }
            return events;
        } catch (DataAccessException | IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    public long pendingEventCount() {
        try {
            Long count = redisTemplate.opsForSet().size(RefreshTokenRiskEventKey.pendingIndex());
            return count == null ? 0 : count;
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    /** marker가 다시 생성된 경우에는 새 인덱스 연결을 보존한다. */
    boolean removeFromPendingIndexIfMarkerMissing(String eventKey) {
        Long removed = redisTemplate.execute(
                REMOVE_STALE_INDEX_MEMBER_SCRIPT,
                List.of(eventKey, RefreshTokenRiskEventKey.pendingIndex()));
        return removed != null && removed > 0;
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
