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
    private final Map<AccountScope, Integer> accountCounts = new HashMap<>();
    private final Map<String, LinkedHashSet<UUID>> routingIndex = new HashMap<>();
    private int correctionOffset;

    synchronized void register(
            SseConnection connection,
            SseRuntimeProperties.RuntimePolicy policy
    ) {
        AccountScope accountScope = AccountScope.from(connection.scope());
        int accountCount = accountCounts.getOrDefault(accountScope, 0);
        if (connections.size() >= policy.maxConnectionsTotal()
                || accountCount >= policy.maxConnectionsPerAccount()) {
            throw new ServiceException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
        connections.put(connection.id(), connection);
        accountCounts.put(accountScope, accountCount + 1);
        addRoutingKeys(connection.id(), connection.routingKeys());
    }

    synchronized void remove(UUID connectionId) {
        SseConnection removed = connections.remove(connectionId);
        if (removed == null) {
            return;
        }
        AccountScope accountScope = AccountScope.from(removed.scope());
        int nextCount = accountCounts.getOrDefault(accountScope, 1) - 1;
        if (nextCount == 0) {
            accountCounts.remove(accountScope);
        } else {
            accountCounts.put(accountScope, nextCount);
        }
        removeRoutingKeys(connectionId, removed.routingKeys());
        if (correctionOffset >= connections.size()) {
            correctionOffset = 0;
        }
    }

    synchronized void updateRoutingKeys(SseStreamScope scope, Set<String> nextKeys) {
        connections.values().stream()
                .filter(connection -> connection.scope().equals(scope))
                .forEach(connection -> {
                    removeRoutingKeys(connection.id(), connection.routingKeys());
                    connection.replaceRoutingKeys(nextKeys);
                    addRoutingKeys(connection.id(), nextKeys);
                });
    }

    synchronized List<SseConnection> findByRoutingKey(String routingKey) {
        Set<UUID> ids = routingIndex.getOrDefault(routingKey, new LinkedHashSet<>());
        return ids.stream().map(connections::get).filter(java.util.Objects::nonNull).toList();
    }

    synchronized List<SseStreamScope> findScopesByRoutingKey(String routingKey) {
        return findByRoutingKey(routingKey).stream()
                .map(SseConnection::scope)
                .distinct()
                .toList();
    }

    synchronized List<SseConnection> findByScope(SseStreamScope scope) {
        return connections.values().stream()
                .filter(connection -> connection.scope().equals(scope))
                .toList();
    }

    synchronized long maxLastSentWatermark(SseStreamScope scope) {
        return connections.values().stream()
                .filter(connection -> connection.scope().equals(scope))
                .mapToLong(SseConnection::lastSentWatermark)
                .max()
                .orElse(-1L);
    }

    synchronized List<SseConnection> all() {
        return List.copyOf(connections.values());
    }

    synchronized List<SseStreamScope> nextCorrectionScopeBatch(int limit) {
        if (limit <= 0 || connections.isEmpty()) {
            return List.of();
        }
        List<SseStreamScope> ordered = connections.values().stream()
                .map(SseConnection::scope)
                .distinct()
                .toList();
        int count = Math.min(limit, ordered.size());
        List<SseStreamScope> batch = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            batch.add(ordered.get((correctionOffset + index) % ordered.size()));
        }
        correctionOffset = (correctionOffset + count) % ordered.size();
        return List.copyOf(batch);
    }

    public synchronized int count() {
        return connections.size();
    }

    private record AccountScope(AuthenticationNamespace namespace, long accountId) {

        private static AccountScope from(SseStreamScope scope) {
            AuthenticationNamespace namespace = scope.audience() == SseAudience.WAITING_STORE_OPERATOR
                    ? AuthenticationNamespace.STORE_OPERATOR
                    : AuthenticationNamespace.CONSUMER;
            return new AccountScope(namespace, scope.accountId());
        }
    }

    private enum AuthenticationNamespace {
        CONSUMER,
        STORE_OPERATOR
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
