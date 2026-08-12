package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 운영자 웨이팅 전이의 명령 유형, canonical fingerprint와 중앙 시각을 구성한다. */
@Service
public class WaitingCommandFacade {

    private static final String PRINCIPAL_NAMESPACE = "store-operator";
    private static final String ROUTE_PREFIX =
            "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/";

    private final WaitingLedgerService ledgerService;
    private final Clock clock;

    public WaitingCommandFacade(WaitingLedgerService ledgerService, Clock clock) {
        this.ledgerService = Objects.requireNonNull(ledgerService);
        this.clock = Objects.requireNonNull(clock);
    }

    public WaitingCommandResult call(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            IdempotencyKey key,
            WaitingTeamTransitionRequest request
    ) {
        return execute(
                operatorAccountId, storeId, waitingTeamId, key, request,
                "WAITING_TEAM_CALL", "call", ledgerService::call
        );
    }

    public WaitingCommandResult arrive(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            IdempotencyKey key,
            WaitingTeamTransitionRequest request
    ) {
        return execute(
                operatorAccountId, storeId, waitingTeamId, key, request,
                "WAITING_TEAM_ARRIVE", "arrive", ledgerService::arrive
        );
    }

    public WaitingCommandResult checkIn(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            IdempotencyKey key,
            WaitingTeamTransitionRequest request
    ) {
        return execute(
                operatorAccountId, storeId, waitingTeamId, key, request,
                "WAITING_TEAM_CHECK_IN", "check-in", ledgerService::checkIn
        );
    }

    public WaitingCommandResult cancel(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            IdempotencyKey key,
            WaitingTeamTransitionRequest request
    ) {
        return execute(
                operatorAccountId, storeId, waitingTeamId, key, request,
                "WAITING_TEAM_CANCEL", "cancel", ledgerService::cancel
        );
    }

    private WaitingCommandResult execute(
            long operatorAccountId,
            long storeId,
            long waitingTeamId,
            IdempotencyKey key,
            WaitingTeamTransitionRequest request,
            String commandType,
            String routeAction,
            LedgerCommand command
    ) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(request, "request must not be null");
        IdempotencyCommand idempotencyCommand = new IdempotencyCommand(
                PRINCIPAL_NAMESPACE,
                operatorAccountId,
                commandType,
                key.value(),
                RequestFingerprint.of(canonical(
                        storeId, waitingTeamId, request.expectedVersion(), routeAction))
        );
        Instant occurredAt = clock.instant();
        return command.execute(
                operatorAccountId,
                storeId,
                waitingTeamId,
                request.expectedVersion(),
                idempotencyCommand,
                occurredAt
        );
    }

    private static String canonical(
            long storeId,
            long waitingTeamId,
            long expectedVersion,
            String routeAction
    ) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "method", "POST");
        append(canonical, "route", ROUTE_PREFIX + routeAction);
        append(canonical, "storeId", Long.toString(storeId));
        append(canonical, "waitingTeamId", Long.toString(waitingTeamId));
        append(canonical, "expectedVersion", Long.toString(expectedVersion));
        return canonical.toString();
    }

    private static void append(StringBuilder target, String field, String value) {
        target.append(field)
                .append('=')
                .append(value.length())
                .append(':')
                .append(value)
                .append('|');
    }

    @FunctionalInterface
    private interface LedgerCommand {
        WaitingCommandResult execute(
                long operatorAccountId,
                long storeId,
                long waitingTeamId,
                long expectedVersion,
                IdempotencyCommand command,
                Instant occurredAt
        );
    }
}
