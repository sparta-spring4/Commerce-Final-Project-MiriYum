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
import org.springframework.stereotype.Component;

/** Valkey pending marker를 읽고 MySQL 전달 성공 뒤에 제거한다. */
@Component
public class ValkeyRefreshTokenRiskEventMarkerStore {

    private final StringRedisTemplate redisTemplate;

    public ValkeyRefreshTokenRiskEventMarkerStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public List<PendingRefreshTokenRiskEvent> findPendingEvents() {
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions()
                .match(RefreshTokenRiskEventKey.pendingPattern())
                .count(100)
                .build())) {
            List<PendingRefreshTokenRiskEvent> events = new ArrayList<>();
            while (cursor.hasNext()) {
                String eventKey = cursor.next();
                Map<String, String> values = redisTemplate.<String, String>opsForHash().entries(eventKey);
                if (!values.isEmpty()) {
                    events.add(toPendingEvent(eventKey, values));
                }
            }
            return events;
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    public void delete(String eventKey) {
        try {
            redisTemplate.delete(eventKey);
        } catch (DataAccessException exception) {
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
                Instant.ofEpochSecond(Long.parseLong(required(values, "occurredAt"))));
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
