package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.payment.service.PaymentPreparationRetryableConflictException;
import com.miriyum.domain.reservation.dto.request.ReservationCreateRequest;
import com.miriyum.domain.reservation.dto.request.ReservationPartyRequest;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.lang.reflect.Constructor;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class ReservationCreationCommandFacadeTest {

    private static final long ACCOUNT_ID = 11L;
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440000"
    );
    private static final ReservationCreateRequest REQUEST = new ReservationCreateRequest(
            "7",
            LocalDate.of(2026, 8, 10),
            LocalTime.of(18, 0),
            "+09:00",
            new ReservationPartyRequest(2, 0, 0),
            List.of()
    );

    @Mock
    private ReservationService reservationService;

    @Test
    void marksTheProductionInjectionConstructorForSpring() throws Exception {
        Constructor<ReservationCreationCommandFacade> constructor =
                ReservationCreationCommandFacade.class.getConstructor(
                        ReservationService.class
                );

        assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
    }

    @Test
    void returnsTheReservationServiceCreationResult() {
        ReservationCreationCommandResult expected =
                new ReservationCreationCommandResult(201, null);
        given(reservationService.createReservation(ACCOUNT_ID, KEY, REQUEST))
                .willReturn(expected);
        ReservationCreationCommandFacade facade =
                new ReservationCreationCommandFacade(reservationService);

        ReservationCreationCommandResult actual = facade.create(ACCOUNT_ID, KEY, REQUEST);

        assertThat(actual).isSameAs(expected);
        then(reservationService).should().createReservation(ACCOUNT_ID, KEY, REQUEST);
    }

    @Test
    void retriesOnePaymentPreparationConflictAndReturnsTheNewTransactionResult() {
        ReservationCreationCommandResult expected =
                new ReservationCreationCommandResult(201, null);
        given(reservationService.createReservation(ACCOUNT_ID, KEY, REQUEST))
                .willThrow(new PaymentPreparationRetryableConflictException())
                .willReturn(expected);
        List<Long> delays = new ArrayList<>();
        ReservationCreationCommandFacade facade = new ReservationCreationCommandFacade(
                reservationService,
                ignored -> 100L,
                delays::add
        );

        ReservationCreationCommandResult actual = facade.create(ACCOUNT_ID, KEY, REQUEST);

        assertThat(actual).isSameAs(expected);
        assertThat(delays).isEmpty();
        then(reservationService).should(times(2)).createReservation(ACCOUNT_ID, KEY, REQUEST);
    }

    @Test
    void propagatesTheSecondPaymentPreparationConflictWithoutAThirdAttempt() {
        PaymentPreparationRetryableConflictException first =
                new PaymentPreparationRetryableConflictException();
        PaymentPreparationRetryableConflictException second =
                new PaymentPreparationRetryableConflictException();
        ReservationCreationCommandResult unexpectedThirdResult =
                new ReservationCreationCommandResult(201, null);
        given(reservationService.createReservation(ACCOUNT_ID, KEY, REQUEST))
                .willThrow(first)
                .willThrow(second)
                .willReturn(unexpectedThirdResult);
        List<Long> delays = new ArrayList<>();
        ReservationCreationCommandFacade facade = new ReservationCreationCommandFacade(
                reservationService,
                ignored -> 100L,
                delays::add
        );

        assertThatThrownBy(() -> facade.create(ACCOUNT_ID, KEY, REQUEST))
                .isSameAs(second);
        assertThat(delays).isEmpty();
        then(reservationService).should(times(2)).createReservation(ACCOUNT_ID, KEY, REQUEST);
    }

    @Test
    void retriesMysqlDeadlocksTwiceAndReturnsTheOriginalServiceResult() {
        ReservationCreationCommandResult expected =
                new ReservationCreationCommandResult(201, null);
        given(reservationService.createReservation(ACCOUNT_ID, KEY, REQUEST))
                .willThrow(mysqlLockFailure(1213))
                .willThrow(mysqlLockFailure(1213))
                .willReturn(expected);
        List<Long> delays = new ArrayList<>();
        ReservationCreationCommandFacade facade = new ReservationCreationCommandFacade(
                reservationService,
                attempt -> attempt == 1 ? 100L : 300L,
                delays::add
        );

        ReservationCreationCommandResult actual = facade.create(ACCOUNT_ID, KEY, REQUEST);

        assertThat(actual).isSameAs(expected);
        assertThat(delays).containsExactly(100L, 300L);
        then(reservationService).should(times(3)).createReservation(ACCOUNT_ID, KEY, REQUEST);
    }

    @Test
    void mapsTheThirdMysqlLockTimeoutToCommon008AndPreservesItsCause() {
        ConcurrencyFailureException failure = mysqlLockFailure(1205);
        given(reservationService.createReservation(ACCOUNT_ID, KEY, REQUEST))
                .willThrow(failure);
        List<Long> delays = new ArrayList<>();
        ReservationCreationCommandFacade facade = new ReservationCreationCommandFacade(
                reservationService,
                attempt -> attempt == 1 ? 200L : 500L,
                delays::add
        );

        assertThatThrownBy(() -> facade.create(ACCOUNT_ID, KEY, REQUEST))
                .isInstanceOfSatisfying(ServiceException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
                    assertThat(exception.getCause()).isSameAs(failure);
                });
        assertThat(delays).containsExactly(200L, 500L);
        then(reservationService).should(times(3)).createReservation(ACCOUNT_ID, KEY, REQUEST);
    }

    @Test
    void interruptedRetrySleepRestoresTheInterruptFlagAndMapsToCommon008() {
        ConcurrencyFailureException failure = mysqlLockFailure(1213);
        InterruptedException interrupted = new InterruptedException("interrupted");
        given(reservationService.createReservation(ACCOUNT_ID, KEY, REQUEST))
                .willThrow(failure);
        ReservationCreationCommandFacade facade = new ReservationCreationCommandFacade(
                reservationService,
                ignored -> 100L,
                ignored -> {
                    throw interrupted;
                }
        );

        try {
            assertThatThrownBy(() -> facade.create(ACCOUNT_ID, KEY, REQUEST))
                    .isInstanceOfSatisfying(ServiceException.class, exception -> {
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
                        assertThat(exception.getCause()).isSameAs(interrupted);
                        assertThat(interrupted.getSuppressed()).containsExactly(failure);
                    });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        then(reservationService).should().createReservation(ACCOUNT_ID, KEY, REQUEST);
    }

    @ParameterizedTest
    @MethodSource("explicitNonRetryableFailuresWithMysqlLockCause")
    void doesNotRetryExplicitNonRetryableTopLevelFailuresWithMysqlLockCause(
            RuntimeException failure
    ) {
        given(reservationService.createReservation(ACCOUNT_ID, KEY, REQUEST))
                .willThrow(failure);
        List<Long> delays = new ArrayList<>();
        ReservationCreationCommandFacade facade = new ReservationCreationCommandFacade(
                reservationService,
                ignored -> 100L,
                delays::add
        );

        assertThatThrownBy(() -> facade.create(ACCOUNT_ID, KEY, REQUEST))
                .isSameAs(failure);
        assertThat(delays).isEmpty();
        then(reservationService).should().createReservation(ACCOUNT_ID, KEY, REQUEST);
    }

    @Test
    void terminatesCyclicTechnicalLockCauseChainsWithoutARetryableVendorCode() {
        GuardedCyclicCauseException first = new GuardedCyclicCauseException();
        GuardedCyclicCauseException second = new GuardedCyclicCauseException();
        first.setNext(second);
        second.setNext(first);
        CannotAcquireLockException failure = new CannotAcquireLockException(
                "cyclic cause chain",
                first
        );
        given(reservationService.createReservation(ACCOUNT_ID, KEY, REQUEST))
                .willThrow(failure);
        List<Long> delays = new ArrayList<>();
        ReservationCreationCommandFacade facade = new ReservationCreationCommandFacade(
                reservationService,
                ignored -> 100L,
                delays::add
        );

        assertThatThrownBy(() -> facade.create(ACCOUNT_ID, KEY, REQUEST))
                .isSameAs(failure);
        assertThat(delays).isEmpty();
        then(reservationService).should().createReservation(ACCOUNT_ID, KEY, REQUEST);
    }

    @Test
    void doesNotRetryMysqlLockVendorCodesOnlyPresentAsSuppressedFailures() {
        CannotAcquireLockException failure = new CannotAcquireLockException(
                "suppressed database failure"
        );
        failure.addSuppressed(new SQLException("deadlock", "40001", 1213));
        given(reservationService.createReservation(ACCOUNT_ID, KEY, REQUEST))
                .willThrow(failure);
        List<Long> delays = new ArrayList<>();
        ReservationCreationCommandFacade facade = new ReservationCreationCommandFacade(
                reservationService,
                ignored -> 100L,
                delays::add
        );

        assertThatThrownBy(() -> facade.create(ACCOUNT_ID, KEY, REQUEST))
                .isSameAs(failure);
        assertThat(delays).isEmpty();
        then(reservationService).should().createReservation(ACCOUNT_ID, KEY, REQUEST);
    }

    @Test
    void usesTheProductionJitterRangesForBothRetryDelays() {
        assertThat(IntStream.range(0, 100)
                .mapToObj(ignored -> ReservationCreationCommandFacade.defaultDelayMillis(1))
                .toList())
                .allSatisfy(delay -> assertThat(delay).isBetween(100L, 200L));
        assertThat(IntStream.range(0, 100)
                .mapToObj(ignored -> ReservationCreationCommandFacade.defaultDelayMillis(2))
                .toList())
                .allSatisfy(delay -> assertThat(delay).isBetween(300L, 500L));
    }

    @ParameterizedTest
    @MethodSource("nonRetryableFailures")
    void propagatesFailuresWithoutADeadlockOrLockTimeoutVendorCode(
            RuntimeException failure
    ) {
        given(reservationService.createReservation(ACCOUNT_ID, KEY, REQUEST))
                .willThrow(failure);
        List<Long> delays = new ArrayList<>();
        ReservationCreationCommandFacade facade = new ReservationCreationCommandFacade(
                reservationService,
                ignored -> 100L,
                delays::add
        );

        assertThatThrownBy(() -> facade.create(ACCOUNT_ID, KEY, REQUEST))
                .isSameAs(failure);
        assertThat(delays).isEmpty();
        then(reservationService).should().createReservation(ACCOUNT_ID, KEY, REQUEST);
    }

    private static Stream<RuntimeException> nonRetryableFailures() {
        return Stream.of(
                new ServiceException(AuthErrorCode.ACCESS_TOKEN_INVALID),
                new ServiceException(AccountErrorCode.RESERVATION_CONTACT_REQUIRED),
                new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT),
                new ServiceException(MenuHoldErrorCode.INSUFFICIENT_QUANTITY),
                new ServiceException(ReservationErrorCode.INSUFFICIENT_CAPACITY),
                new ServiceException(ReservationErrorCode.DUPLICATE_RESERVATION),
                new IllegalArgumentException("invalid request"),
                new ConcurrencyFailureException("no vendor failure"),
                new QueryTimeoutException("query timeout"),
                new DataIntegrityViolationException(
                        "other MySQL vendor error",
                        new SQLException("duplicate key", "23000", 1062)
                )
        );
    }

    private static Stream<RuntimeException>
            explicitNonRetryableFailuresWithMysqlLockCause() {
        return Stream.of(
                withCause(
                        new ServiceException(ReservationErrorCode.DUPLICATE_RESERVATION),
                        new SQLException("deadlock", "40001", 1213)
                ),
                withCause(
                        new IllegalArgumentException("invalid request"),
                        new SQLException("lock wait timeout", "HY000", 1205)
                ),
                withCause(
                        new QueryTimeoutException("query timeout"),
                        new SQLException("deadlock", "40001", 1213)
                ),
                withCause(
                        new DataIntegrityViolationException("constraint failure"),
                        new SQLException("lock wait timeout", "HY000", 1205)
                ),
                withCause(
                        new ConcurrencyFailureException("generic concurrency failure"),
                        new SQLException("deadlock", "40001", 1213)
                )
        );
    }

    private static <T extends RuntimeException> T withCause(T exception, Throwable cause) {
        exception.initCause(cause);
        return exception;
    }

    private static CannotAcquireLockException mysqlLockFailure(int errorCode) {
        return new CannotAcquireLockException(
                "database lock failure",
                new SQLException("mysql lock failure", "40001", errorCode)
        );
    }

    private static final class GuardedCyclicCauseException extends RuntimeException {

        private Throwable next;
        private int remainingCauseReads = 2;

        private GuardedCyclicCauseException() {
            super("cyclic cause");
        }

        private void setNext(Throwable next) {
            this.next = next;
        }

        @Override
        public synchronized Throwable getCause() {
            if (remainingCauseReads-- == 0) {
                throw new AssertionError("cause traversal did not terminate");
            }
            return next;
        }
    }
}
