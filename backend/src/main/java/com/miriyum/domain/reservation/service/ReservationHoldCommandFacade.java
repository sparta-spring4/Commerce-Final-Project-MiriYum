package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.ReservationHoldContracts;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntToLongFunction;
import java.util.function.Supplier;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선점 생성·종결의 기술적 DB lock 충돌만 transaction 바깥에서 제한 재시도한다.
 *
 * <p>각 service 호출이 새 transaction을 소유하며 승인된 command unique 충돌만
 * 실패 transaction 종료 뒤 한 번 replay한다.</p>
 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class ReservationHoldCommandFacade {

    private static final int MAX_ATTEMPTS = 3;
    private static final String CREATION_COMMAND_CONSTRAINT =
            "uk_reservation_holds_creation_command";
    private static final String TRANSITION_COMMAND_CONSTRAINT =
            "uk_reservation_hold_transition_audits_command";

    private final ReservationHoldService holdService;
    private final IntToLongFunction retryDelayMillis;
    private final RetrySleeper retrySleeper;

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public ReservationHoldCommandFacade(ReservationHoldService holdService) {
        this(
                holdService,
                ReservationHoldCommandFacade::defaultDelayMillis,
                Thread::sleep);
    }

    ReservationHoldCommandFacade(
            ReservationHoldService holdService,
            IntToLongFunction retryDelayMillis,
            RetrySleeper retrySleeper
    ) {
        this.holdService = Objects.requireNonNull(
                holdService, "holdService must not be null");
        this.retryDelayMillis = Objects.requireNonNull(
                retryDelayMillis, "retryDelayMillis must not be null");
        this.retrySleeper = Objects.requireNonNull(
                retrySleeper, "retrySleeper must not be null");
    }

    /**
     * 수용량 선점을 생성하고 승인된 생성 command 경합은 기존 결과로 replay한다.
     *
     * @param command 인증된 소비자의 선점 생성 명령
     * @return 새로 커밋되거나 replay된 현재 선점 결과
     */
    public ReservationHoldContracts.Result create(
            ReservationHoldContracts.CreateCommand command
    ) {
        return execute(
                () -> holdService.create(command),
                CREATION_COMMAND_CONSTRAINT);
    }

    /**
     * 선점을 명시적으로 종결하고 승인된 operation 경합은 기존 결과로 replay한다.
     *
     * @param command 상위 조정자가 검증한 선점 종결 명령
     * @return 새로 커밋되거나 replay된 현재 선점 결과
     */
    public ReservationHoldContracts.Result transition(
            ReservationHoldContracts.TransitionCommand command
    ) {
        return execute(
                () -> holdService.transition(command),
                TRANSITION_COMMAND_CONSTRAINT);
    }

    private <T> T execute(Supplier<T> command, String replayConstraint) {
        try {
            return executeWithLockRetry(command);
        } catch (DataIntegrityViolationException failure) {
            if (!hasStructuredConstraintName(failure, replayConstraint)) {
                throw failure;
            }
            return executeWithLockRetry(command);
        }
    }

    private <T> T executeWithLockRetry(Supplier<T> command) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return command.get();
            } catch (RuntimeException failure) {
                if (!isRetryableTechnicalLockFailure(failure)) {
                    throw failure;
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw concurrentModification(failure);
                }
                sleepBeforeRetry(attempt, failure);
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }

    private void sleepBeforeRetry(int failedAttempt, RuntimeException failure) {
        try {
            retrySleeper.sleep(retryDelayMillis.applyAsLong(failedAttempt));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            interrupted.addSuppressed(failure);
            throw concurrentModification(interrupted);
        }
    }

    static long defaultDelayMillis(int failedAttempt) {
        return switch (failedAttempt) {
            case 1 -> ThreadLocalRandom.current().nextLong(100L, 201L);
            case 2 -> ThreadLocalRandom.current().nextLong(300L, 501L);
            default -> throw new IllegalArgumentException("unsupported retry attempt");
        };
    }

    private static boolean isRetryableTechnicalLockFailure(RuntimeException failure) {
        if (failure instanceof ServiceException
                || failure instanceof IllegalArgumentException
                || failure instanceof QueryTimeoutException
                || failure instanceof DataIntegrityViolationException
                || !(failure instanceof CannotAcquireLockException)) {
            return false;
        }
        return causeChainContains(failure, current ->
                current instanceof SQLException sqlException
                        && (sqlException.getErrorCode() == 1213
                        || sqlException.getErrorCode() == 1205));
    }

    private static boolean hasStructuredConstraintName(
            Throwable failure,
            String expectedConstraint
    ) {
        return causeChainContains(failure, current ->
                current instanceof ConstraintViolationException violation
                        && isMysqlDuplicateKey(violation)
                        && matchesConstraintName(
                                violation.getConstraintName(), expectedConstraint));
    }

    private static boolean isMysqlDuplicateKey(ConstraintViolationException violation) {
        return violation.getSQLException() != null
                && violation.getSQLException().getErrorCode() == 1062;
    }

    private static boolean matchesConstraintName(
            String actualConstraint,
            String expectedConstraint
    ) {
        return expectedConstraint.equals(actualConstraint)
                || qualifiedConstraintName(expectedConstraint).equals(actualConstraint);
    }

    private static String qualifiedConstraintName(String expectedConstraint) {
        return switch (expectedConstraint) {
            case CREATION_COMMAND_CONSTRAINT ->
                    "reservation_holds." + CREATION_COMMAND_CONSTRAINT;
            case TRANSITION_COMMAND_CONSTRAINT ->
                    "reservation_hold_transition_audits." + TRANSITION_COMMAND_CONSTRAINT;
            default -> throw new IllegalArgumentException("unsupported replay constraint");
        };
    }

    private static boolean causeChainContains(
            Throwable failure,
            java.util.function.Predicate<Throwable> predicate
    ) {
        Throwable current = failure;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (predicate.test(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static ServiceException concurrentModification(Throwable cause) {
        ServiceException conflict = new ServiceException(
                CommonErrorCode.CONCURRENT_MODIFICATION);
        conflict.initCause(cause);
        return conflict;
    }
}
