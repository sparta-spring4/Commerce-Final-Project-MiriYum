package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;

import com.miriyum.domain.reservation.dto.request.ConsumerCancellationRequest;
import com.miriyum.domain.reservation.dto.request.StoreCancellationRequest;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.RequestFingerprint;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToLongFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class ReservationCancellationCommandFacadeTest {

    private static final String FACADE_CLASS_NAME =
            "com.miriyum.domain.reservation.service.ReservationCancellationCommandFacade";
    private static final long CONSUMER_ID = 11L;
    private static final long OPERATOR_ID = Long.MAX_VALUE;
    private static final long STORE_ID = 73L;
    private static final long RESERVATION_ID = 321L;
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-09T02:03:04Z");
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550E8400-E29B-41D4-A716-446655440000"
    );
    private static final ReservationCancellationCommandResult RESULT =
            new ReservationCancellationCommandResult(200, null);

    @Mock
    private ReservationService reservationService;

    @Test
    void commandConsumerBuildsScopedFingerprintAndCorrelation() throws Exception {
        RecordingClock clock = new RecordingClock(REQUESTED_AT);
        ConsumerCancellationRequest request = new ConsumerCancellationRequest(null);
        given(reservationService.cancelConsumerReservation(
                anyLong(), anyLong(), any(), nullable(String.class), any(), anyString()))
                .willReturn(RESULT);
        Object facade = newFacade(clock, ignored -> 100L, ignored -> { });

        Object actual = invokeConsumer(facade, CONSUMER_ID, RESERVATION_ID, KEY, request);

        assertThat(actual).isSameAs(RESULT);
        Class<?> facadeType = facadeClass();
        Constructor<?> productionConstructor = facadeType.getConstructor(
                ReservationService.class, Clock.class);
        assertThat(productionConstructor.isAnnotationPresent(Autowired.class)).isTrue();
        assertThat(facadeType.getMethod(
                "cancelByConsumer",
                long.class,
                long.class,
                IdempotencyKey.class,
                ConsumerCancellationRequest.class
        ).getReturnType()).isEqualTo(ReservationCancellationCommandResult.class);

        ArgumentCaptor<IdempotencyCommand> command =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> requestedAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<String> correlation = ArgumentCaptor.forClass(String.class);
        then(reservationService).should().cancelConsumerReservation(
                org.mockito.ArgumentMatchers.eq(CONSUMER_ID),
                org.mockito.ArgumentMatchers.eq(RESERVATION_ID),
                command.capture(),
                reason.capture(),
                requestedAt.capture(),
                correlation.capture()
        );
        assertThat(command.getValue()).isEqualTo(new IdempotencyCommand(
                "consumer",
                CONSUMER_ID,
                "RESERVATION_CANCEL",
                "550e8400-e29b-41d4-a716-446655440000",
                RequestFingerprint.of(
                        "method=4:POST|"
                                + "route=63:/api/v1/consumers/me/reservations/{reservationId}/cancellations|"
                                + "reservationId=3:321|"
                                + "reason=-1:|"
                )
        ));
        assertThat(reason.getValue()).isNull();
        assertThat(requestedAt.getValue()).isSameAs(REQUESTED_AT);
        assertThat(correlation.getValue()).isEqualTo(
                "reservation-cancel:consumer:11:550e8400-e29b-41d4-a716-446655440000");
        assertThat(correlation.getValue()).doesNotContain("321");
        assertThat(clock.instantCalls()).isEqualTo(1);
    }

    @Test
    void commandOperatorIncludesStoreAndReservationInFingerprint() throws Exception {
        RecordingClock clock = new RecordingClock(REQUESTED_AT);
        StoreCancellationRequest request = new StoreCancellationRequest("store closed");
        given(reservationService.cancelStoreReservation(
                anyLong(), anyLong(), anyLong(), any(), anyString(), any(), anyString()))
                .willReturn(RESULT);
        Object facade = newFacade(clock, ignored -> 100L, ignored -> { });

        Object actual = invokeOperator(
                facade, OPERATOR_ID, STORE_ID, RESERVATION_ID, KEY, request);

        assertThat(actual).isSameAs(RESULT);
        assertThat(facadeClass().getMethod(
                "cancelByStoreOperator",
                long.class,
                long.class,
                long.class,
                IdempotencyKey.class,
                StoreCancellationRequest.class
        ).getReturnType()).isEqualTo(ReservationCancellationCommandResult.class);
        ArgumentCaptor<IdempotencyCommand> command =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        ArgumentCaptor<Instant> requestedAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<String> correlation = ArgumentCaptor.forClass(String.class);
        then(reservationService).should().cancelStoreReservation(
                org.mockito.ArgumentMatchers.eq(OPERATOR_ID),
                org.mockito.ArgumentMatchers.eq(STORE_ID),
                org.mockito.ArgumentMatchers.eq(RESERVATION_ID),
                command.capture(),
                org.mockito.ArgumentMatchers.eq("store closed"),
                requestedAt.capture(),
                correlation.capture()
        );
        assertThat(command.getValue()).isEqualTo(new IdempotencyCommand(
                "store-operator",
                OPERATOR_ID,
                "RESERVATION_CANCEL",
                "550e8400-e29b-41d4-a716-446655440000",
                RequestFingerprint.of(
                        "method=4:POST|"
                                + "route=83:/api/v1/store-operators/stores/{storeId}/reservations/"
                                + "{reservationId}/cancellations|"
                                + "storeId=2:73|"
                                + "reservationId=3:321|"
                                + "reason=12:store closed|"
                )
        ));
        assertThat(requestedAt.getValue()).isSameAs(REQUESTED_AT);
        assertThat(correlation.getValue()).doesNotContain("321");
        assertThat(clock.instantCalls()).isEqualTo(1);
    }

    @Test
    void commandDifferentRouteOrReasonChangesFingerprint() throws Exception {
        RecordingClock clock = new RecordingClock(REQUESTED_AT);
        given(reservationService.cancelConsumerReservation(
                anyLong(), anyLong(), any(), anyString(), any(), anyString()))
                .willReturn(RESULT);
        given(reservationService.cancelStoreReservation(
                anyLong(), anyLong(), anyLong(), any(), anyString(), any(), anyString()))
                .willReturn(RESULT);
        Object facade = newFacade(clock, ignored -> 100L, ignored -> { });

        invokeConsumer(
                facade,
                CONSUMER_ID,
                RESERVATION_ID,
                KEY,
                new ConsumerCancellationRequest("reason-a")
        );
        invokeConsumer(
                facade,
                CONSUMER_ID,
                RESERVATION_ID,
                KEY,
                new ConsumerCancellationRequest("reason-b")
        );
        invokeOperator(
                facade,
                CONSUMER_ID,
                STORE_ID,
                RESERVATION_ID,
                KEY,
                new StoreCancellationRequest("reason-a")
        );

        ArgumentCaptor<IdempotencyCommand> consumerCommands =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(reservationService).should(times(2)).cancelConsumerReservation(
                anyLong(), anyLong(), consumerCommands.capture(), anyString(), any(), anyString());
        ArgumentCaptor<IdempotencyCommand> operatorCommand =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(reservationService).should().cancelStoreReservation(
                anyLong(), anyLong(), anyLong(), operatorCommand.capture(),
                anyString(), any(), anyString());

        assertThat(consumerCommands.getAllValues().get(0).requestFingerprint())
                .isEqualTo(RequestFingerprint.of(
                        "method=4:POST|"
                                + "route=63:/api/v1/consumers/me/reservations/{reservationId}/cancellations|"
                                + "reservationId=3:321|"
                                + "reason=8:reason-a|"
                ));
        assertThat(consumerCommands.getAllValues().get(1).requestFingerprint())
                .isEqualTo(RequestFingerprint.of(
                        "method=4:POST|"
                                + "route=63:/api/v1/consumers/me/reservations/{reservationId}/cancellations|"
                                + "reservationId=3:321|"
                                + "reason=8:reason-b|"
                ));
        assertThat(operatorCommand.getValue().requestFingerprint())
                .isEqualTo(RequestFingerprint.of(
                        "method=4:POST|"
                                + "route=83:/api/v1/store-operators/stores/{storeId}/reservations/"
                                + "{reservationId}/cancellations|"
                                + "storeId=2:73|"
                                + "reservationId=3:321|"
                                + "reason=8:reason-a|"
                ));
        assertThat(consumerCommands.getAllValues().get(0).requestFingerprint())
                .isNotEqualTo(consumerCommands.getAllValues().get(1).requestFingerprint())
                .isNotEqualTo(operatorCommand.getValue().requestFingerprint());
    }

    @Test
    void commandNullSingleAndBoundaryWhitespaceRemainDistinctWithoutNormalization()
            throws Exception {
        RecordingClock clock = new RecordingClock(REQUESTED_AT);
        given(reservationService.cancelConsumerReservation(
                anyLong(), anyLong(), any(), nullable(String.class), any(), anyString()))
                .willReturn(RESULT);
        Object facade = newFacade(clock, ignored -> 100L, ignored -> { });

        invokeConsumer(
                facade, CONSUMER_ID, RESERVATION_ID, KEY,
                new ConsumerCancellationRequest(null));
        invokeConsumer(
                facade, CONSUMER_ID, RESERVATION_ID, KEY,
                new ConsumerCancellationRequest(" "));
        invokeConsumer(
                facade, CONSUMER_ID, RESERVATION_ID, KEY,
                new ConsumerCancellationRequest(" leading"));
        invokeConsumer(
                facade, CONSUMER_ID, RESERVATION_ID, KEY,
                new ConsumerCancellationRequest("trailing "));

        ArgumentCaptor<IdempotencyCommand> commands =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        ArgumentCaptor<String> reasons = ArgumentCaptor.forClass(String.class);
        then(reservationService).should(times(4)).cancelConsumerReservation(
                anyLong(), anyLong(), commands.capture(), reasons.capture(), any(), anyString());

        assertThat(reasons.getAllValues())
                .containsExactly(null, " ", " leading", "trailing ");
        assertThat(commands.getAllValues())
                .extracting(IdempotencyCommand::requestFingerprint)
                .containsExactly(
                        RequestFingerprint.of(
                                "method=4:POST|"
                                        + "route=63:/api/v1/consumers/me/reservations/{reservationId}/"
                                        + "cancellations|"
                                        + "reservationId=3:321|"
                                        + "reason=-1:|"
                        ),
                        RequestFingerprint.of(
                                "method=4:POST|"
                                        + "route=63:/api/v1/consumers/me/reservations/{reservationId}/"
                                        + "cancellations|"
                                        + "reservationId=3:321|"
                                        + "reason=1: |"
                        ),
                        RequestFingerprint.of(
                                "method=4:POST|"
                                        + "route=63:/api/v1/consumers/me/reservations/{reservationId}/"
                                        + "cancellations|"
                                        + "reservationId=3:321|"
                                        + "reason=8: leading|"
                        ),
                        RequestFingerprint.of(
                                "method=4:POST|"
                                        + "route=63:/api/v1/consumers/me/reservations/{reservationId}/"
                                        + "cancellations|"
                                        + "reservationId=3:321|"
                                        + "reason=9:trailing |"
                        )
                )
                .doesNotHaveDuplicates();
        assertThat(clock.instantCalls()).isEqualTo(4);
    }

    @Test
    void commandExposesOnlyTheExactActorSpecificPublicEntries() {
        assertThat(Stream.of(facadeClass().getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList())
                .containsExactlyInAnyOrder("cancelByConsumer", "cancelByStoreOperator");
    }

    @Test
    void commandLongMaxOperatorCorrelationIsExactlyNinetyCharacters() throws Exception {
        given(reservationService.cancelStoreReservation(
                anyLong(), anyLong(), anyLong(), any(), anyString(), any(), anyString()))
                .willReturn(RESULT);
        Object facade = newFacade(
                new RecordingClock(REQUESTED_AT), ignored -> 100L, ignored -> { });

        invokeOperator(
                facade,
                Long.MAX_VALUE,
                STORE_ID,
                RESERVATION_ID,
                KEY,
                new StoreCancellationRequest("closed")
        );

        ArgumentCaptor<String> correlation = ArgumentCaptor.forClass(String.class);
        then(reservationService).should().cancelStoreReservation(
                anyLong(), anyLong(), anyLong(), any(), anyString(), any(), correlation.capture());
        assertThat(correlation.getValue())
                .isEqualTo(
                        "reservation-cancel:store-operator:9223372036854775807:"
                                + "550e8400-e29b-41d4-a716-446655440000")
                .hasSize(90)
                .doesNotContain("321");
    }

    @Test
    void commandRejectsNullKeyAndRequestsBeforeReadingClock() throws Exception {
        RecordingClock clock = new RecordingClock(REQUESTED_AT);
        Object facade = newFacade(clock, ignored -> 100L, ignored -> { });

        assertThatThrownBy(() -> invokeConsumer(
                facade,
                CONSUMER_ID,
                RESERVATION_ID,
                null,
                new ConsumerCancellationRequest(null)
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> invokeConsumer(
                facade, CONSUMER_ID, RESERVATION_ID, KEY, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> invokeOperator(
                facade,
                CONSUMER_ID,
                STORE_ID,
                RESERVATION_ID,
                null,
                new StoreCancellationRequest("closed")
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> invokeOperator(
                facade, CONSUMER_ID, STORE_ID, RESERVATION_ID, KEY, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(clock.instantCalls()).isZero();
        then(reservationService).shouldHaveNoInteractions();
    }

    @Test
    void retryDeadlockThenTimeoutUsesThreeAttemptsAndOneRequestedAt() throws Exception {
        String exactReason = " retry reason ";
        given(reservationService.cancelConsumerReservation(
                anyLong(), anyLong(), any(), anyString(), any(), anyString()))
                .willThrow(mysqlLockFailure(1213))
                .willThrow(mysqlLockFailure(1205))
                .willReturn(RESULT);
        RecordingClock clock = new RecordingClock(REQUESTED_AT);
        List<Long> delays = new ArrayList<>();
        Object facade = newFacade(
                clock,
                attempt -> attempt == 1 ? 100L : 300L,
                delays::add
        );

        Object actual = invokeConsumer(
                facade,
                CONSUMER_ID,
                RESERVATION_ID,
                KEY,
                new ConsumerCancellationRequest(exactReason)
        );

        assertThat(actual).isSameAs(RESULT);
        assertThat(delays).containsExactly(100L, 300L);
        ArgumentCaptor<IdempotencyCommand> commands =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        ArgumentCaptor<String> reasons = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> requestedTimes = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<String> correlations = ArgumentCaptor.forClass(String.class);
        then(reservationService).should(times(3)).cancelConsumerReservation(
                anyLong(), anyLong(), commands.capture(), reasons.capture(),
                requestedTimes.capture(), correlations.capture());
        IdempotencyCommand firstCommand = commands.getAllValues().get(0);
        assertThat(commands.getAllValues()).allSatisfy(
                command -> assertThat(command).isSameAs(firstCommand));
        assertThat(reasons.getAllValues())
                .containsExactly(exactReason, exactReason, exactReason)
                .allSatisfy(reason -> assertThat(reason).isSameAs(exactReason));
        assertThat(requestedTimes.getAllValues()).allSatisfy(
                requestedAt -> assertThat(requestedAt).isSameAs(REQUESTED_AT));
        String exactCorrelation =
                "reservation-cancel:consumer:11:550e8400-e29b-41d4-a716-446655440000";
        assertThat(correlations.getAllValues())
                .containsExactly(exactCorrelation, exactCorrelation, exactCorrelation);
        String firstCorrelation = correlations.getAllValues().get(0);
        assertThat(correlations.getAllValues()).allSatisfy(
                correlation -> assertThat(correlation).isSameAs(firstCorrelation));
        assertThat(clock.instantCalls()).isEqualTo(1);
    }

    @Test
    void retryThirdTechnicalFailureMapsCommon008WithCause() throws Exception {
        CannotAcquireLockException failure = mysqlLockFailure(1213);
        given(reservationService.cancelConsumerReservation(
                anyLong(), anyLong(), any(), nullable(String.class), any(), anyString()))
                .willThrow(failure);
        List<Long> delays = new ArrayList<>();
        Object facade = newFacade(
                new RecordingClock(REQUESTED_AT),
                attempt -> attempt == 1 ? 100L : 300L,
                delays::add
        );

        assertThatThrownBy(() -> invokeConsumer(
                facade,
                CONSUMER_ID,
                RESERVATION_ID,
                KEY,
                new ConsumerCancellationRequest(null)
        )).isInstanceOfSatisfying(ServiceException.class, exception -> {
            assertThat(exception.getErrorCode())
                    .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
            assertThat(exception.getCause()).isSameAs(failure);
        });
        assertThat(delays).containsExactly(100L, 300L);
        then(reservationService).should(times(3)).cancelConsumerReservation(
                anyLong(), anyLong(), any(), nullable(String.class), any(), anyString());
    }

    @Test
    void retryOptimisticConflictUsesTheSameBoundedPolicy() throws Exception {
        ObjectOptimisticLockingFailureException failure =
                new ObjectOptimisticLockingFailureException("optimistic conflict", null);
        given(reservationService.cancelConsumerReservation(
                anyLong(), anyLong(), any(), nullable(String.class), any(), anyString()))
                .willThrow(failure)
                .willThrow(failure)
                .willReturn(RESULT);
        List<Long> delays = new ArrayList<>();
        Object facade = newFacade(
                new RecordingClock(REQUESTED_AT),
                attempt -> attempt == 1 ? 100L : 300L,
                delays::add
        );

        Object actual = invokeConsumer(
                facade,
                CONSUMER_ID,
                RESERVATION_ID,
                KEY,
                new ConsumerCancellationRequest(null)
        );

        assertThat(actual).isSameAs(RESULT);
        assertThat(delays).containsExactly(100L, 300L);
        then(reservationService).should(times(3)).cancelConsumerReservation(
                anyLong(), anyLong(), any(), nullable(String.class), any(), anyString());
    }

    @Test
    void retryDomainValidationQueryTimeoutDataIntegrityAndUnrelatedLockDoNotRetry()
            throws Exception {
        for (RuntimeException failure : nonRetryableFailures().toList()) {
            reset(reservationService);
            given(reservationService.cancelConsumerReservation(
                    anyLong(), anyLong(), any(), nullable(String.class), any(), anyString()))
                    .willThrow(failure);
            List<Long> delays = new ArrayList<>();
            Object facade = newFacade(
                    new RecordingClock(REQUESTED_AT), ignored -> 100L, delays::add);

            assertThatThrownBy(() -> invokeConsumer(
                    facade,
                    CONSUMER_ID,
                    RESERVATION_ID,
                    KEY,
                    new ConsumerCancellationRequest(null)
            )).isSameAs(failure);
            assertThat(delays).isEmpty();
            then(reservationService).should().cancelConsumerReservation(
                    anyLong(), anyLong(), any(), nullable(String.class), any(), anyString());
        }
    }

    @Test
    void retryInterruptionRestoresFlagAndMapsCommon008() throws Exception {
        CannotAcquireLockException failure = mysqlLockFailure(1213);
        InterruptedException interrupted = new InterruptedException("interrupted");
        given(reservationService.cancelConsumerReservation(
                anyLong(), anyLong(), any(), nullable(String.class), any(), anyString()))
                .willThrow(failure);
        Object facade = newFacade(
                new RecordingClock(REQUESTED_AT),
                ignored -> 100L,
                ignored -> {
                    throw interrupted;
                }
        );

        try {
            assertThatThrownBy(() -> invokeConsumer(
                    facade,
                    CONSUMER_ID,
                    RESERVATION_ID,
                    KEY,
                    new ConsumerCancellationRequest(null)
            )).isInstanceOfSatisfying(ServiceException.class, exception -> {
                assertThat(exception.getErrorCode())
                        .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
                assertThat(exception.getCause()).isSameAs(interrupted);
                assertThat(interrupted.getSuppressed()).containsExactly(failure);
            });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        then(reservationService).should().cancelConsumerReservation(
                anyLong(), anyLong(), any(), nullable(String.class), any(), anyString());
    }

    @Test
    void retryProductionJitterStaysInApprovedRanges() throws Exception {
        Method defaultDelay = facadeClass().getDeclaredMethod("defaultDelayMillis", int.class);
        defaultDelay.setAccessible(true);

        assertThat(IntStream.range(0, 100)
                .mapToObj(ignored -> invokeDelay(defaultDelay, 1))
                .toList())
                .allSatisfy(delay -> assertThat(delay).isBetween(100L, 200L));
        assertThat(IntStream.range(0, 100)
                .mapToObj(ignored -> invokeDelay(defaultDelay, 2))
                .toList())
                .allSatisfy(delay -> assertThat(delay).isBetween(300L, 500L));
    }

    private Object newFacade(
            Clock clock,
            IntToLongFunction delay,
            ThrowingSleeper sleeper
    ) throws Exception {
        Class<?> facadeType = facadeClass();
        Class<?> sleeperType = Class.forName(FACADE_CLASS_NAME + "$RetrySleeper");
        Object sleeperProxy = Proxy.newProxyInstance(
                sleeperType.getClassLoader(),
                new Class<?>[]{sleeperType},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("sleep")) {
                        sleeper.sleep((long) arguments[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
        Constructor<?> constructor = facadeType.getDeclaredConstructor(
                ReservationService.class,
                Clock.class,
                IntToLongFunction.class,
                sleeperType
        );
        constructor.setAccessible(true);
        return constructor.newInstance(reservationService, clock, delay, sleeperProxy);
    }

    private static Object invokeConsumer(
            Object facade,
            long consumerAccountId,
            long reservationId,
            IdempotencyKey key,
            ConsumerCancellationRequest request
    ) throws Exception {
        return invoke(
                facade,
                "cancelByConsumer",
                new Class<?>[]{
                        long.class,
                        long.class,
                        IdempotencyKey.class,
                        ConsumerCancellationRequest.class
                },
                consumerAccountId,
                reservationId,
                key,
                request
        );
    }

    private static Object invokeOperator(
            Object facade,
            long operatorAccountId,
            long storeId,
            long reservationId,
            IdempotencyKey key,
            StoreCancellationRequest request
    ) throws Exception {
        return invoke(
                facade,
                "cancelByStoreOperator",
                new Class<?>[]{
                        long.class,
                        long.class,
                        long.class,
                        IdempotencyKey.class,
                        StoreCancellationRequest.class
                },
                operatorAccountId,
                storeId,
                reservationId,
                key,
                request
        );
    }

    private static Object invoke(
            Object facade,
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        Method method;
        try {
            method = facade.getClass().getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException exception) {
            return fail(
                    "ReservationCancellationCommandFacade."
                            + methodName
                            + " public method is required",
                    exception
            );
        }
        try {
            return method.invoke(facade, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }

    private static Class<?> facadeClass() {
        try {
            return Class.forName(FACADE_CLASS_NAME);
        } catch (ClassNotFoundException exception) {
            return fail("ReservationCancellationCommandFacade class is required", exception);
        }
    }

    private static long invokeDelay(Method method, int attempt) {
        try {
            return (long) method.invoke(null, attempt);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new AssertionError("default retry delay must be invokable", exception);
        }
    }

    private static Stream<RuntimeException> nonRetryableFailures() {
        return Stream.of(
                new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION),
                new IllegalArgumentException("invalid request"),
                new QueryTimeoutException("query timeout"),
                new DataIntegrityViolationException(
                        "constraint failure",
                        new SQLException("duplicate key", "23000", 1062)
                ),
                new CannotAcquireLockException(
                        "unrelated lock failure",
                        new SQLException("unrelated", "HY000", 1062)
                )
        );
    }

    private static CannotAcquireLockException mysqlLockFailure(int errorCode) {
        return new CannotAcquireLockException(
                "database lock failure",
                new SQLException("mysql lock failure", "40001", errorCode)
        );
    }

    @FunctionalInterface
    private interface ThrowingSleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final class RecordingClock extends Clock {

        private final Instant firstInstant;
        private int instantCalls;

        private RecordingClock(Instant firstInstant) {
            this.firstInstant = firstInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return firstInstant.plusSeconds(instantCalls++);
        }

        private int instantCalls() {
            return instantCalls;
        }
    }
}
