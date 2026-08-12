package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.miriyum.domain.reservation.dto.request.ReservationFulfillmentRequest;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntToLongFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class ReservationFulfillmentCommandFacadeTest {

    private static final long OPERATOR_ID = 33L;
    private static final long STORE_ID = 73L;
    private static final long RESERVATION_ID = 321L;
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-10T00:00:00Z");
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440000"
    );
    private static final ReservationFulfillmentRequest REQUEST =
            new ReservationFulfillmentRequest();
    private static final ReservationFulfillmentCommandResult RESULT =
            new ReservationFulfillmentCommandResult(200, null);
    private static final String CORRELATION =
            "reservation-fulfill:store-operator:33:550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private ReservationService reservationService;

    private ReservationFulfillmentCommandFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ReservationFulfillmentCommandFacade(
                reservationService,
                Clock.fixed(REQUESTED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void buildsExactScopedCommandAndNinetyOneCharacterCorrelation() {
        facade.fulfill(
                Long.MAX_VALUE,
                STORE_ID,
                RESERVATION_ID,
                KEY,
                new ReservationFulfillmentRequest()
        );

        ArgumentCaptor<IdempotencyCommand> command =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        ArgumentCaptor<String> correlation = ArgumentCaptor.forClass(String.class);
        then(reservationService).should().fulfillStoreReservation(
                eq(Long.MAX_VALUE),
                eq(STORE_ID),
                eq(RESERVATION_ID),
                command.capture(),
                eq(REQUESTED_AT),
                correlation.capture()
        );
        assertThat(command.getValue().commandType()).isEqualTo("RESERVATION_FULFILL");
        assertThat(command.getValue().requestFingerprint()).isEqualTo(
                RequestFingerprint.of(
                        "method=4:POST|"
                                + "route=82:/api/v1/store-operators/stores/{storeId}/"
                                + "reservations/{reservationId}/fulfillments|"
                                + "storeId=2:73|reservationId=3:321|"
                )
        );
        assertThat(correlation.getValue())
                .isEqualTo(
                        "reservation-fulfill:store-operator:9223372036854775807:"
                                + "550e8400-e29b-41d4-a716-446655440000"
                )
                .hasSize(91);
    }

    @Test
    void retriesDeadlockThenLockTimeoutWithSameCommandTimeAndCorrelation() {
        given(reservationService.fulfillStoreReservation(
                anyLong(), anyLong(), anyLong(), any(), any(), anyString()
        )).willThrow(mysqlLockFailure(1213))
                .willThrow(mysqlLockFailure(1205))
                .willReturn(RESULT);
        RecordingClock clock = new RecordingClock(REQUESTED_AT);
        List<Long> delays = new ArrayList<>();
        ReservationFulfillmentCommandFacade retryingFacade =
                newFacade(clock, attempt -> attempt == 1 ? 100L : 300L, delays::add);

        assertThat(retryingFacade.fulfill(
                OPERATOR_ID,
                STORE_ID,
                RESERVATION_ID,
                KEY,
                REQUEST
        )).isSameAs(RESULT);
        ArgumentCaptor<IdempotencyCommand> commands =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        ArgumentCaptor<Instant> times = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<String> correlations = ArgumentCaptor.forClass(String.class);
        then(reservationService).should(times(3)).fulfillStoreReservation(
                eq(OPERATOR_ID),
                eq(STORE_ID),
                eq(RESERVATION_ID),
                commands.capture(),
                times.capture(),
                correlations.capture()
        );
        assertThat(commands.getAllValues()).allSatisfy(value ->
                assertThat(value).isSameAs(commands.getAllValues().getFirst()));
        assertThat(times.getAllValues()).allSatisfy(value ->
                assertThat(value).isSameAs(REQUESTED_AT));
        assertThat(correlations.getAllValues()).containsOnly(CORRELATION);
        String firstCorrelation = correlations.getAllValues().getFirst();
        assertThat(correlations.getAllValues()).allSatisfy(value ->
                assertThat(value).isSameAs(firstCorrelation));
        assertThat(clock.instantCalls()).isEqualTo(1);
        assertThat(delays).containsExactly(100L, 300L);
    }

    @ParameterizedTest
    @MethodSource("nonRetryableFailures")
    void doesNotRetryDomainValidationTimeoutIntegrityOrUnrelatedLock(
            RuntimeException failure
    ) {
        given(reservationService.fulfillStoreReservation(
                anyLong(), anyLong(), anyLong(), any(), any(), anyString()
        )).willThrow(failure);
        List<Long> delays = new ArrayList<>();
        ReservationFulfillmentCommandFacade retryingFacade = newFacade(
                new RecordingClock(REQUESTED_AT),
                ignored -> 100L,
                delays::add
        );

        assertThatThrownBy(() -> retryingFacade.fulfill(
                OPERATOR_ID,
                STORE_ID,
                RESERVATION_ID,
                KEY,
                REQUEST
        )).isSameAs(failure);
        then(reservationService).should().fulfillStoreReservation(
                anyLong(), anyLong(), anyLong(), any(), any(), anyString()
        );
        assertThat(delays).isEmpty();
    }

    private static Stream<RuntimeException> nonRetryableFailures() {
        return Stream.of(
                new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION),
                new IllegalArgumentException("invalid"),
                new QueryTimeoutException("timeout"),
                new DataIntegrityViolationException(
                        "constraint",
                        new SQLException("duplicate", "23000", 1062)
                ),
                new CannotAcquireLockException(
                        "unrelated",
                        new SQLException("unrelated", "HY000", 1062)
                )
        );
    }

    private static CannotAcquireLockException mysqlLockFailure(int code) {
        return new CannotAcquireLockException(
                "lock",
                new SQLException("mysql lock", "40001", code)
        );
    }

    @Test
    void retriesOptimisticConflictAndReturnsSecondAttempt() {
        ObjectOptimisticLockingFailureException first =
                new ObjectOptimisticLockingFailureException(
                        Reservation.class,
                        RESERVATION_ID
                );
        given(reservationService.fulfillStoreReservation(
                anyLong(), anyLong(), anyLong(), any(), any(), anyString()
        )).willThrow(first).willReturn(RESULT);
        List<Long> delays = new ArrayList<>();
        ReservationFulfillmentCommandFacade retryingFacade = newFacade(
                new RecordingClock(REQUESTED_AT),
                ignored -> 100L,
                delays::add
        );

        assertThat(retryingFacade.fulfill(
                OPERATOR_ID,
                STORE_ID,
                RESERVATION_ID,
                KEY,
                REQUEST
        )).isSameAs(RESULT);
        then(reservationService).should(times(2)).fulfillStoreReservation(
                anyLong(), anyLong(), anyLong(), any(), any(), anyString()
        );
        assertThat(delays).containsExactly(100L);
    }

    @Test
    void thirdRetryableFailureBecomesCommon008AndPreservesCause() {
        CannotAcquireLockException failure = mysqlLockFailure(1213);
        given(reservationService.fulfillStoreReservation(
                anyLong(), anyLong(), anyLong(), any(), any(), anyString()
        )).willThrow(failure, failure, failure);
        ReservationFulfillmentCommandFacade retryingFacade = newFacade(
                new RecordingClock(REQUESTED_AT),
                ignored -> 100L,
                ignored -> {
                }
        );

        assertThatThrownBy(() -> retryingFacade.fulfill(
                OPERATOR_ID,
                STORE_ID,
                RESERVATION_ID,
                KEY,
                REQUEST
        )).isInstanceOf(ServiceException.class)
                .satisfies(error -> {
                    ServiceException conflict = (ServiceException) error;
                    assertThat(conflict.getErrorCode())
                            .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
                    assertThat(conflict.getCause()).isSameAs(failure);
                });
        then(reservationService).should(times(3)).fulfillStoreReservation(
                anyLong(), anyLong(), anyLong(), any(), any(), anyString()
        );
    }

    @Test
    void interruptedRetryRestoresFlagAndBecomesCommon008() {
        CannotAcquireLockException failure = mysqlLockFailure(1205);
        given(reservationService.fulfillStoreReservation(
                anyLong(), anyLong(), anyLong(), any(), any(), anyString()
        )).willThrow(failure);
        ReservationFulfillmentCommandFacade retryingFacade = newFacade(
                new RecordingClock(REQUESTED_AT),
                ignored -> 100L,
                ignored -> {
                    throw new InterruptedException("interrupted");
                }
        );
        Thread.interrupted();
        try {
            assertThatThrownBy(() -> retryingFacade.fulfill(
                    OPERATOR_ID,
                    STORE_ID,
                    RESERVATION_ID,
                    KEY,
                    REQUEST
            )).isInstanceOf(ServiceException.class)
                    .satisfies(error -> assertThat(
                            ((ServiceException) error).getErrorCode()
                    ).isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void productionRetryDelayStaysInsideApprovedRanges() {
        for (int sample = 0; sample < 200; sample++) {
            assertThat(ReservationFulfillmentCommandFacade.defaultDelayMillis(1))
                    .isBetween(100L, 200L);
            assertThat(ReservationFulfillmentCommandFacade.defaultDelayMillis(2))
                    .isBetween(300L, 500L);
        }
    }

    private ReservationFulfillmentCommandFacade newFacade(
            Clock clock,
            IntToLongFunction retryDelayMillis,
            ReservationFulfillmentCommandFacade.RetrySleeper retrySleeper
    ) {
        return new ReservationFulfillmentCommandFacade(
                reservationService,
                clock,
                retryDelayMillis,
                retrySleeper
        );
    }

    private static final class RecordingClock extends Clock {

        private final Instant instant;
        private final AtomicInteger calls = new AtomicInteger();

        private RecordingClock(Instant instant) {
            this.instant = instant;
        }

        int instantCalls() {
            return calls.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            calls.incrementAndGet();
            return instant;
        }
    }
}
