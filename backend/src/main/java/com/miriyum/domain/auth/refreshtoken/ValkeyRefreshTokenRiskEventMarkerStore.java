package com.miriyum.domain.auth.refreshtoken;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ValueScanCursor;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Valkey pending marker를 읽고 MySQL 전달 성공 뒤에 제거한다. */
@Component
public class ValkeyRefreshTokenRiskEventMarkerStore {

    private static final Logger log = LoggerFactory.getLogger(ValkeyRefreshTokenRiskEventMarkerStore.class);
    private static final long PENDING_EVENT_SCAN_COUNT = 100L;
    private static final List<String> PENDING_EVENT_FIELDS = List.of(
            "namespace", "accountId", "familyId", "tokenHash", "sourceEvent",
            "originEvent", "policyVersion", "occurredAt", "occurrenceCount", "lastOccurredAt");

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

    private static final RedisScript<Long> REMOVE_MALFORMED_PENDING_MARKER_SCRIPT = new DefaultRedisScript<>("""
            for index = 1, #ARGV, 3 do
                local field = ARGV[index]
                local expectedPresent = ARGV[index + 1]
                local expectedValue = ARGV[index + 2]
                local actualValue = redis.call('HGET', KEYS[1], field)
                if expectedPresent == '0' then
                    if actualValue ~= false then
                        return 0
                    end
                elseif actualValue == false or actualValue ~= expectedValue then
                    return 0
                end
            end
            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[2], KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Object pendingIndexScanMonitor = new Object();
    private final Deque<String> pendingMarkerKeyBuffer = new ArrayDeque<>();
    private String pendingIndexScanCursor = "0";

    public ValkeyRefreshTokenRiskEventMarkerStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public List<PendingRefreshTokenRiskEvent> findPendingEvents() {
        try {
            List<PendingRefreshTokenRiskEvent> events = new ArrayList<>();
            for (String eventKey : nextPendingMarkerKeys()) {
                Map<String, String> values = redisTemplate.<String, String>opsForHash().entries(eventKey);
                if (!values.isEmpty()) {
                    try {
                        events.add(toPendingEvent(eventKey, values));
                    } catch (IllegalArgumentException exception) {
                        quarantineMalformedPendingMarker(eventKey, values);
                    }
                } else {
                    try {
                        removeFromPendingIndexIfMarkerMissing(eventKey);
                    } catch (DataAccessException | ServiceException exception) {
                        log.warn("event=refresh_token_risk_event_stale_index_cleanup_failed");
                    }
                }
            }
            return events;
        } catch (DataAccessException | IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    private List<String> nextPendingMarkerKeys() {
        synchronized (pendingIndexScanMonitor) {
            List<String> markerKeys = new ArrayList<>();
            if (!pendingMarkerKeyBuffer.isEmpty()) {
                drainPendingMarkerKeyBuffer(markerKeys);
                return markerKeys;
            }

            boolean completedScanCycle = false;
            while (markerKeys.size() < PENDING_EVENT_SCAN_COUNT) {
                if (pendingMarkerKeyBuffer.isEmpty()) {
                    if (completedScanCycle) {
                        break;
                    }
                    completedScanCycle = scanNextPendingIndexPage();
                    if (pendingMarkerKeyBuffer.isEmpty() && completedScanCycle) {
                        break;
                    }
                }
                drainPendingMarkerKeyBuffer(markerKeys);
            }
            return markerKeys;
        }
    }

    private void drainPendingMarkerKeyBuffer(List<String> markerKeys) {
        while (markerKeys.size() < PENDING_EVENT_SCAN_COUNT && !pendingMarkerKeyBuffer.isEmpty()) {
            markerKeys.add(pendingMarkerKeyBuffer.removeFirst());
        }
    }

    private boolean scanNextPendingIndexPage() {
        ValueScanCursor<byte[]> scanResult;
        try {
            scanResult = redisTemplate.execute(
                    (RedisCallback<ValueScanCursor<byte[]>>) connection -> {
                        Object nativeConnection = connection.getNativeConnection();
                        if (!(nativeConnection instanceof RedisClusterAsyncCommands<?, ?>)) {
                            throw unavailable();
                        }
                        @SuppressWarnings("unchecked")
                        RedisClusterAsyncCommands<byte[], byte[]> commands =
                                (RedisClusterAsyncCommands<byte[], byte[]>) nativeConnection;
                        return commands.sscan(
                                        utf8(RefreshTokenRiskEventKey.pendingIndex()),
                                        ScanCursor.of(pendingIndexScanCursor),
                                        new ScanArgs().limit(PENDING_EVENT_SCAN_COUNT))
                                .toCompletableFuture()
                                .join();
                    });
        } catch (CompletionException exception) {
            throw unavailable();
        }
        if (scanResult == null) {
            throw unavailable();
        }

        pendingIndexScanCursor = scanResult.getCursor();
        for (byte[] value : scanResult.getValues()) {
            pendingMarkerKeyBuffer.addLast(new String(value, StandardCharsets.UTF_8));
        }
        return "0".equals(pendingIndexScanCursor);
    }

    private byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
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

    private void quarantineMalformedPendingMarker(String eventKey, Map<String, String> values) {
        try {
            Long removed = redisTemplate.execute(
                    REMOVE_MALFORMED_PENDING_MARKER_SCRIPT,
                    List.of(eventKey, RefreshTokenRiskEventKey.pendingIndex()),
                    malformedMarkerSnapshot(values));
            if (removed != null && removed > 0) {
                log.warn("event=refresh_token_risk_event_marker_malformed");
            } else {
                log.warn("event=refresh_token_risk_event_marker_malformed");
                log.error("event=refresh_token_risk_event_marker_quarantine_mismatch");
            }
        } catch (DataAccessException | ServiceException exception) {
            log.warn("event=refresh_token_risk_event_marker_quarantine_failed");
        }
    }

    private Object[] malformedMarkerSnapshot(Map<String, String> values) {
        List<String> snapshot = new ArrayList<>(PENDING_EVENT_FIELDS.size() * 3);
        for (String field : PENDING_EVENT_FIELDS) {
            String value = values.get(field);
            snapshot.add(field);
            snapshot.add(value == null ? "0" : "1");
            snapshot.add(value == null ? "" : value);
        }
        return snapshot.toArray();
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
        try {
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
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Malformed pending risk event", exception);
        }
    }

    private String required(Map<String, String> values, String field) {
        String value = values.get(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing pending risk event field: " + field);
        }
        return value;
    }

    private ServiceException unavailable() {
        return new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
    }
}
