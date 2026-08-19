package com.miriyum.global.sse;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 인스턴스 로컬 연결 수와 opaque routing index를 제한된 크기로 유지한다. */
@Component
public class SseConnectionRegistry {

    private final Map<UUID, SseConnection> connections = new LinkedHashMap<>();
    private final Map<Long, Integer> accountCounts = new HashMap<>();
    private final Map<String, LinkedHashSet<UUID>> routingIndex = new HashMap<>();
    private int correctionOffset;

    synchronized void register(
            SseConnection connection,
            SseRuntimeProperties.RuntimePolicy policy
    ) {
        int accountCount = accountCounts.getOrDefault(connection.accountId(), 0);
        if (connections.size() >= policy.maxConnectionsTotal()
                || accountCount >= policy.maxConnectionsPerAccount()) {
            throw new ServiceException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
        connections.put(connection.id(), connection);
        accountCounts.put(connection.accountId(), accountCount + 1);
        addRoutingKeys(connection.id(), connection.routingKeys());
    }

    synchronized void remove(UUID connectionId) {
        SseConnection removed = connections.remove(connectionId);
        if (removed == null) {
            return;
        }
        int nextCount = accountCounts.getOrDefault(removed.accountId(), 1) - 1;
        if (nextCount == 0) {
            accountCounts.remove(removed.accountId());
        } else {
            accountCounts.put(removed.accountId(), nextCount);
        }
        removeRoutingKeys(connectionId, removed.routingKeys());
        if (correctionOffset >= connections.size()) {
            correctionOffset = 0;
        }
    }

    synchronized void updateRoutingKeys(UUID connectionId, Set<String> nextKeys) {
        SseConnection connection = connections.get(connectionId);
        if (connection == null) {
            return;
        }
        removeRoutingKeys(connectionId, connection.routingKeys());
        connection.replaceRoutingKeys(nextKeys);
        addRoutingKeys(connectionId, nextKeys);
    }

    synchronized List<SseConnection> findByRoutingKey(String routingKey) {
        Set<UUID> ids = routingIndex.getOrDefault(routingKey, new LinkedHashSet<>());
        return ids.stream().map(connections::get).filter(java.util.Objects::nonNull).toList();
    }

    synchronized List<SseConnection> all() {
        return List.copyOf(connections.values());
    }

    synchronized List<SseConnection> nextCorrectionBatch(int limit) {
        if (limit <= 0 || connections.isEmpty()) {
            return List.of();
        }
        List<SseConnection> ordered = new ArrayList<>(connections.values());
        int count = Math.min(limit, ordered.size());
        List<SseConnection> batch = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            batch.add(ordered.get((correctionOffset + index) % ordered.size()));
        }
        correctionOffset = (correctionOffset + count) % ordered.size();
        return List.copyOf(batch);
    }

    public synchronized int count() {
        return connections.size();
    }

    private void addRoutingKeys(UUID id, Set<String> keys) {
        keys.forEach(key -> routingIndex
                .computeIfAbsent(key, ignored -> new LinkedHashSet<>())
                .add(id));
    }

    private void removeRoutingKeys(UUID id, Set<String> keys) {
        keys.forEach(key -> {
            Set<UUID> ids = routingIndex.get(key);
            if (ids == null) {
                return;
            }
            ids.remove(id);
            if (ids.isEmpty()) {
                routingIndex.remove(key);
            }
        });
    }
}
