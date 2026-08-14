package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.miriyum.domain.reservation.waiting.dto.WaitingCommandResult;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingTeamTransitionRequest;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

@ExtendWith(MockitoExtension.class)
class WaitingCommandFacadeTest {

    private static final long OPERATOR_ID = 11L;
    private static final long STORE_ID = 21L;
    private static final long TEAM_ID = 31L;
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-12T03:01:00Z");
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440000");

    @Mock
    private WaitingLedgerService ledgerService;

    private WaitingCommandFacade facade;
    private WaitingCommandResult firstResult;

    @BeforeEach
    void setUp() {
        facade = new WaitingCommandFacade(
                ledgerService,
                Clock.fixed(REQUESTED_AT, ZoneOffset.UTC)
        );
        firstResult = new WaitingCommandResult(200, new WaitingTeamSnapshot(
                Long.toString(TEAM_ID),
                Long.toString(STORE_ID),
                WaitingTeamStatus.CALLED,
                7L,
                2,
                Instant.parse("2026-08-12T03:00:00Z"),
                REQUESTED_AT,
                null,
                null,
                null,
                1L
        ));
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("call fingerprint contains only the canonical request fields")
    void callFingerprintContainsOnlyCanonicalRequestFields() {
        given(ledgerService.call(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any()))
                .willReturn(firstResult);

        facade.call(
                OPERATOR_ID,
                STORE_ID,
                TEAM_ID,
                KEY,
                new WaitingTeamTransitionRequest(4L)
        );

        ArgumentCaptor<IdempotencyCommand> command =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(ledgerService).should().call(
                org.mockito.ArgumentMatchers.eq(OPERATOR_ID),
                org.mockito.ArgumentMatchers.eq(STORE_ID),
                org.mockito.ArgumentMatchers.eq(TEAM_ID),
                org.mockito.ArgumentMatchers.eq(4L),
                command.capture(),
                org.mockito.ArgumentMatchers.eq(REQUESTED_AT)
        );
        assertThat(command.getValue().commandType()).isEqualTo("WAITING_TEAM_CALL");
        assertThat(command.getValue().requestFingerprint()).isEqualTo(RequestFingerprint.of(
                "method=4:POST|"
                        + "route=76:/api/v1/store-operators/stores/{storeId}/waiting-teams/"
                        + "{waitingTeamId}/calls|"
                        + "storeId=2:21|"
                        + "waitingTeamId=2:31|"
                        + "expectedVersion=1:4|"
        ));
    }

    @Test
    @DisplayName("arrive fingerprint uses the canonical route and version")
    void arriveFingerprintUsesCanonicalRouteAndVersion() {
        given(ledgerService.arrive(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any()))
                .willReturn(firstResult);

        facade.arrive(
                OPERATOR_ID, STORE_ID, TEAM_ID, KEY, new WaitingTeamTransitionRequest(1L));

        ArgumentCaptor<IdempotencyCommand> command =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(ledgerService).should().arrive(
                org.mockito.ArgumentMatchers.eq(OPERATOR_ID),
                org.mockito.ArgumentMatchers.eq(STORE_ID),
                org.mockito.ArgumentMatchers.eq(TEAM_ID),
                org.mockito.ArgumentMatchers.eq(1L),
                command.capture(),
                org.mockito.ArgumentMatchers.eq(REQUESTED_AT));
        assertThat(command.getValue().commandType()).isEqualTo("WAITING_TEAM_ARRIVE");
        assertThat(command.getValue().requestFingerprint()).isEqualTo(RequestFingerprint.of(
                canonical("arrivals", 1L)));
    }

    @Test
    @DisplayName("check-in fingerprint uses the canonical route and version")
    void checkInFingerprintUsesCanonicalRouteAndVersion() {
        given(ledgerService.checkIn(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any()))
                .willReturn(firstResult);

        facade.checkIn(
                OPERATOR_ID, STORE_ID, TEAM_ID, KEY, new WaitingTeamTransitionRequest(2L));

        ArgumentCaptor<IdempotencyCommand> command =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(ledgerService).should().checkIn(
                org.mockito.ArgumentMatchers.eq(OPERATOR_ID),
                org.mockito.ArgumentMatchers.eq(STORE_ID),
                org.mockito.ArgumentMatchers.eq(TEAM_ID),
                org.mockito.ArgumentMatchers.eq(2L),
                command.capture(),
                org.mockito.ArgumentMatchers.eq(REQUESTED_AT));
        assertThat(command.getValue().commandType()).isEqualTo("WAITING_TEAM_CHECK_IN");
        assertThat(command.getValue().requestFingerprint()).isEqualTo(RequestFingerprint.of(
                canonical("check-ins", 2L)));
    }

    @Test
    @DisplayName("cancel fingerprint uses the canonical route and version")
    void cancelFingerprintUsesCanonicalRouteAndVersion() {
        given(ledgerService.cancel(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any()))
                .willReturn(firstResult);

        facade.cancel(
                OPERATOR_ID, STORE_ID, TEAM_ID, KEY, new WaitingTeamTransitionRequest(2L));

        ArgumentCaptor<IdempotencyCommand> command =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(ledgerService).should().cancel(
                org.mockito.ArgumentMatchers.eq(OPERATOR_ID),
                org.mockito.ArgumentMatchers.eq(STORE_ID),
                org.mockito.ArgumentMatchers.eq(TEAM_ID),
                org.mockito.ArgumentMatchers.eq(2L),
                command.capture(),
                org.mockito.ArgumentMatchers.eq(REQUESTED_AT));
        assertThat(command.getValue().commandType()).isEqualTo("WAITING_TEAM_CANCEL");
        assertThat(command.getValue().requestFingerprint()).isEqualTo(RequestFingerprint.of(
                canonical("cancellations", 2L)));
    }

    @Test
    @DisplayName("a deadlock retry reuses the exact command and occurredAt")
    void retriesMysqlDeadlockWithSameCommandAndOccurredAt() {
        RecordingClock clock = new RecordingClock(REQUESTED_AT);
        List<Long> delays = new ArrayList<>();
        facade = new WaitingCommandFacade(
                ledgerService, clock, attempt -> attempt * 100L, delays::add);
        given(ledgerService.call(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any()))
                .willThrow(mysqlLockFailure(1213))
                .willThrow(mysqlLockFailure(1213))
                .willReturn(firstResult);

        WaitingCommandResult result = facade.call(
                OPERATOR_ID, STORE_ID, TEAM_ID, KEY, new WaitingTeamTransitionRequest(0L));

        assertThat(result).isSameAs(firstResult);
        assertThat(delays).containsExactly(100L, 200L);
        assertThat(clock.instantCalls()).isOne();
        ArgumentCaptor<IdempotencyCommand> commands =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        ArgumentCaptor<Instant> occurredAt = ArgumentCaptor.forClass(Instant.class);
        then(ledgerService).should(times(3)).call(
                org.mockito.ArgumentMatchers.eq(OPERATOR_ID),
                org.mockito.ArgumentMatchers.eq(STORE_ID),
                org.mockito.ArgumentMatchers.eq(TEAM_ID),
                org.mockito.ArgumentMatchers.eq(0L),
                commands.capture(),
                occurredAt.capture());
        assertThat(commands.getAllValues())
                .allSatisfy(command -> assertThat(command).isSameAs(commands.getValue()));
        assertThat(occurredAt.getAllValues()).containsOnly(REQUESTED_AT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retryableFailures")
    @DisplayName("exhausted technical concurrency failures map to COMMON_008")
    void exhaustedTechnicalConcurrencyFailuresMapToCommon008(
            String description,
            RuntimeException failure
    ) {
        List<Long> delays = new ArrayList<>();
        facade = new WaitingCommandFacade(
                ledgerService,
                Clock.fixed(REQUESTED_AT, ZoneOffset.UTC),
                attempt -> attempt,
                delays::add);
        given(ledgerService.call(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any()))
                .willThrow(failure);

        assertThatThrownBy(() -> facade.call(
                OPERATOR_ID, STORE_ID, TEAM_ID, KEY, new WaitingTeamTransitionRequest(0L)))
                .isInstanceOfSatisfying(ServiceException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
                    assertThat(exception.getCause()).isSameAs(failure);
                });
        assertThat(delays).containsExactly(1L, 2L);
        then(ledgerService).should(times(3)).call(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonRetryableFailures")
    @DisplayName("semantic, validation, idempotency, and generic database failures are not retried")
    void doesNotRetryNonTechnicalFailures(String description, RuntimeException failure) {
        List<Long> delays = new ArrayList<>();
        facade = new WaitingCommandFacade(
                ledgerService,
                Clock.fixed(REQUESTED_AT, ZoneOffset.UTC),
                attempt -> attempt,
                delays::add);
        given(ledgerService.call(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any()))
                .willThrow(failure);

        Throwable thrown = catchThrowable(() -> facade.call(
                OPERATOR_ID, STORE_ID, TEAM_ID, KEY, new WaitingTeamTransitionRequest(0L)));

        assertThat(thrown).isSameAs(failure);
        assertThat(delays).isEmpty();
        then(ledgerService).should(times(1)).call(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any());
    }

    @Test
    @DisplayName("an interrupted retry restores interruption and maps to COMMON_008")
    void interruptedRetryMapsToCommon008() {
        RuntimeException failure = mysqlLockFailure(1213);
        facade = new WaitingCommandFacade(
                ledgerService,
                Clock.fixed(REQUESTED_AT, ZoneOffset.UTC),
                attempt -> 0L,
                millis -> {
                    throw new InterruptedException("stop");
                });
        given(ledgerService.call(
                any(Long.class), any(Long.class), any(Long.class), any(Long.class), any(), any()))
                .willThrow(failure);

        assertThatThrownBy(() -> facade.call(
                OPERATOR_ID, STORE_ID, TEAM_ID, KEY, new WaitingTeamTransitionRequest(0L)))
                .isInstanceOfSatisfying(ServiceException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
                    assertThat(exception.getCause()).isInstanceOf(InterruptedException.class);
                    assertThat(exception.getCause().getSuppressed()).containsExactly(failure);
                });
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private static Stream<Arguments> retryableFailures() {
        return Stream.of(
                Arguments.of("MySQL deadlock", mysqlLockFailure(1213)),
                Arguments.of("MySQL lock timeout", mysqlLockFailure(1205)),
                Arguments.of("query timeout", new QueryTimeoutException("timed out")),
                Arguments.of("transaction timeout", new TransactionTimedOutException("timed out"))
        );
    }

    private static Stream<Arguments> nonRetryableFailures() {
        return Stream.of(
                Arguments.of("domain service error",
                        new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED)),
                Arguments.of("validation error", new IllegalArgumentException("invalid")),
                Arguments.of("generic constraint failure",
                        new DataIntegrityViolationException("constraint")),
                Arguments.of("unknown lock failure",
                        new CannotAcquireLockException("unknown lock"))
        );
    }

    private static CannotAcquireLockException mysqlLockFailure(int errorCode) {
        return new CannotAcquireLockException(
                "lock conflict", new SQLException("mysql lock", "40001", errorCode));
    }

    private static String canonical(String action, long expectedVersion) {
        String route = "/api/v1/store-operators/stores/{storeId}/waiting-teams/"
                + "{waitingTeamId}/" + action;
        return "method=4:POST|"
                + "route=" + route.length() + ":" + route + "|"
                + "storeId=2:21|"
                + "waitingTeamId=2:31|"
                + "expectedVersion=1:" + expectedVersion + "|";
    }

    private static final class RecordingClock extends Clock {
        private final Instant instant;
        private int instantCalls;

        private RecordingClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            instantCalls++;
            return instant;
        }

        private int instantCalls() {
            return instantCalls;
        }
    }
}
