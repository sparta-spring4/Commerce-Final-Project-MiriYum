package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.miriyum.domain.reservation.dto.ReservationHoldContracts;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class ReservationHoldCommandFacadeTest {

    private static final ReservationHoldContracts.CreateCommand CREATE_COMMAND =
            new ReservationHoldContracts.CreateCommand(
                    11L,
                    22L,
                    LocalDate.of(2026, 8, 3),
                    java.time.LocalTime.of(18, 0),
                    null,
                    2,
                    1,
                    0,
                    "hold-create-1");
    private static final ReservationHoldContracts.TransitionCommand TRANSITION_COMMAND =
            new ReservationHoldContracts.TransitionCommand(
                    77L,
                    ReservationHoldStatus.RELEASED,
                    "hold-transition-1",
                    "SYSTEM",
                    null,
                    Instant.parse("2026-08-03T00:00:00Z"));

    @Mock
    private ReservationHoldService holdService;

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @ParameterizedTest(name = "MySQL {0}")
    @MethodSource("retryableMysqlCodes")
    @DisplayName("MySQL deadlock과 lock timeout만 최대 세 번의 새 service 호출로 재시도한다")
    void retriesOnlyApprovedMysqlLockFailures(int mysqlCode) {
        ReservationHoldContracts.Result expected = result(ReservationHoldStatus.ACTIVE);
        List<Long> delays = new ArrayList<>();
        ReservationHoldCommandFacade facade = facade(delays);
        given(holdService.create(CREATE_COMMAND))
                .willThrow(lockFailure(mysqlCode))
                .willThrow(lockFailure(mysqlCode))
                .willReturn(expected);

        ReservationHoldContracts.Result actual = facade.create(CREATE_COMMAND);

        assertThat(actual).isSameAs(expected);
        assertThat(delays).containsExactly(101L, 302L);
        then(holdService).should(times(3)).create(CREATE_COMMAND);
    }

    private static Stream<Arguments> retryableMysqlCodes() {
        return Stream.of(Arguments.of(1213), Arguments.of(1205));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonRetryableFailures")
    @DisplayName("업무 입력 query timeout 일반 integrity와 비승인 lock 오류는 재시도하지 않는다")
    void propagatesNonRetryableFailureOnce(
            String ignoredDescription,
            RuntimeException failure
    ) {
        List<Long> delays = new ArrayList<>();
        ReservationHoldCommandFacade facade = facade(delays);
        given(holdService.transition(TRANSITION_COMMAND)).willThrow(failure);

        assertThatThrownBy(() -> facade.transition(TRANSITION_COMMAND))
                .isSameAs(failure);

        assertThat(delays).isEmpty();
        then(holdService).should(times(1)).transition(TRANSITION_COMMAND);
    }

    private static Stream<Arguments> nonRetryableFailures() {
        return Stream.of(
                Arguments.of("business", new ServiceException(
                        ReservationErrorCode.INVALID_STATE_TRANSITION)),
                Arguments.of("input", new IllegalArgumentException("bad command")),
                Arguments.of("query timeout", new QueryTimeoutException("query timed out")),
                Arguments.of("integrity", new DataIntegrityViolationException("fk failed")),
                Arguments.of("other lock code", lockFailure(9999))
        );
    }

    @Test
    @DisplayName("생성 승인 unique 충돌은 실패 transaction 뒤 같은 command를 한 번 replay한다")
    void replaysCreateOnceAfterApprovedUniqueConflict() {
        ReservationHoldContracts.Result expected = result(ReservationHoldStatus.ACTIVE);
        ReservationHoldCommandFacade facade = facade(new ArrayList<>());
        given(holdService.create(CREATE_COMMAND))
                .willThrow(uniqueConflict("uk_reservation_holds_creation_command"))
                .willReturn(expected);

        ReservationHoldContracts.Result actual = facade.create(CREATE_COMMAND);

        assertThat(actual).isSameAs(expected);
        then(holdService).should(times(2)).create(CREATE_COMMAND);
    }

    @Test
    @DisplayName("종결 승인 unique 충돌은 실패 transaction 뒤 같은 command를 한 번 replay한다")
    void replaysTransitionOnceAfterApprovedUniqueConflict() {
        ReservationHoldContracts.Result expected = result(ReservationHoldStatus.RELEASED);
        ReservationHoldCommandFacade facade = facade(new ArrayList<>());
        given(holdService.transition(TRANSITION_COMMAND))
                .willThrow(uniqueConflict(
                        "uk_reservation_hold_transition_audits_command"))
                .willReturn(expected);

        ReservationHoldContracts.Result actual = facade.transition(TRANSITION_COMMAND);

        assertThat(actual).isSameAs(expected);
        then(holdService).should(times(2)).transition(TRANSITION_COMMAND);
    }

    @Test
    @DisplayName("구조화된 이름이 비승인 constraint면 message에 승인 이름이 있어도 replay하지 않는다")
    void doesNotGuessApprovedConstraintFromMessage() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "duplicate uk_reservation_holds_creation_command",
                hibernateConstraint("uk_other_unique"));
        ReservationHoldCommandFacade facade = facade(new ArrayList<>());
        given(holdService.create(CREATE_COMMAND)).willThrow(failure);

        assertThatThrownBy(() -> facade.create(CREATE_COMMAND)).isSameAs(failure);

        then(holdService).should(times(1)).create(CREATE_COMMAND);
    }

    @Test
    @DisplayName("기술 lock 재시도 세 번이 모두 실패하면 COMMON_008이다")
    void exhaustedLockRetriesReturnConcurrentModification() {
        List<Long> delays = new ArrayList<>();
        ReservationHoldCommandFacade facade = facade(delays);
        given(holdService.create(CREATE_COMMAND)).willThrow(lockFailure(1213));

        assertThatThrownBy(() -> facade.create(CREATE_COMMAND))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION));

        assertThat(delays).containsExactly(101L, 302L);
        then(holdService).should(times(3)).create(CREATE_COMMAND);
    }

    @Test
    @DisplayName("재시도 대기 interrupt는 flag를 보존하고 COMMON_008로 종료한다")
    void interruptedRetryPreservesFlagAndReturnsConcurrentModification() {
        ReservationHoldCommandFacade facade = new ReservationHoldCommandFacade(
                holdService,
                attempt -> 100L,
                millis -> {
                    throw new InterruptedException("interrupted");
                });
        given(holdService.create(CREATE_COMMAND)).willThrow(lockFailure(1205));

        assertThatThrownBy(() -> facade.create(CREATE_COMMAND))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        then(holdService).should(times(1)).create(CREATE_COMMAND);
    }

    @Test
    @DisplayName("cause chain cycle은 무한 순회하지 않고 비재시도 오류로 전파한다")
    void causeChainCycleTerminatesWithoutRetry() {
        CannotAcquireLockException outer = new CannotAcquireLockException("lock");
        RuntimeException inner = new RuntimeException("cycle");
        outer.initCause(inner);
        inner.initCause(outer);
        ReservationHoldCommandFacade facade = facade(new ArrayList<>());
        given(holdService.create(CREATE_COMMAND)).willThrow(outer);

        assertThatThrownBy(() -> facade.create(CREATE_COMMAND)).isSameAs(outer);

        then(holdService).should(times(1)).create(CREATE_COMMAND);
    }

    private ReservationHoldCommandFacade facade(List<Long> delays) {
        return new ReservationHoldCommandFacade(
                holdService,
                attempt -> attempt == 1 ? 101L : 302L,
                delays::add);
    }

    private static CannotAcquireLockException lockFailure(int mysqlCode) {
        return new CannotAcquireLockException(
                "lock failed",
                new SQLException("mysql lock", "40001", mysqlCode));
    }

    private static DataIntegrityViolationException uniqueConflict(String constraintName) {
        return new DataIntegrityViolationException(
                "integrity conflict",
                hibernateConstraint(constraintName));
    }

    private static ConstraintViolationException hibernateConstraint(String constraintName) {
        return new ConstraintViolationException(
                "constraint conflict",
                new SQLException("duplicate", "23000", 1062),
                "insert into reservation_holds",
                constraintName);
    }

    private static ReservationHoldContracts.Result result(ReservationHoldStatus status) {
        return new ReservationHoldContracts.Result(
                77L,
                status,
                0L,
                11L,
                22L,
                LocalDate.of(2026, 8, 3),
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"),
                Instant.parse("2026-08-03T10:15:00Z"),
                "Asia/Seoul",
                2,
                1,
                0,
                5L,
                7L,
                1L,
                Instant.parse("2026-08-03T00:00:00Z"),
                Instant.parse("2026-08-03T00:10:00Z"));
    }
}
