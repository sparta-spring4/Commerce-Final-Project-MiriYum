package com.miriyum.domain.pickup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.miriyum.domain.pickup.dto.request.PickupCancellationRequest;
import com.miriyum.domain.pickup.dto.request.PickupMenuSelectionRequest;
import com.miriyum.domain.pickup.dto.request.PickupReservationCreateRequest;
import com.miriyum.domain.pickup.dto.request.StorePickupCancellationRequest;
import com.miriyum.domain.pickup.entity.PickupReservation;
import com.miriyum.domain.pickup.exception.PickupErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class PickupCommandFacadeTest {

    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440000");
    private static final PickupReservationCreateRequest CREATE_REQUEST =
            new PickupReservationCreateRequest(
                    "22", LocalDate.of(2026, 8, 10), LocalTime.NOON,
                    List.of(new PickupMenuSelectionRequest("33", 1)));

    @Mock PickupReservationService reservationService;
    @Mock PickupStoreManagementService managementService;

    @Test
    void retriesTwoMysqlDeadlocksInSeparateServiceCallsThenReturnsSuccess() {
        PickupCommandResult expected = new PickupCommandResult(201, null);
        given(reservationService.create(11L, KEY, CREATE_REQUEST))
                .willThrow(mysqlLockFailure(1213))
                .willThrow(mysqlLockFailure(1213))
                .willReturn(expected);
        List<Long> delays = new ArrayList<>();
        PickupCommandFacade facade = facade(delays);

        PickupCommandResult result = facade.create(11L, KEY, CREATE_REQUEST);

        assertThat(result).isSameAs(expected);
        assertThat(delays).containsExactly(100L, 300L);
        then(reservationService).should(times(3)).create(11L, KEY, CREATE_REQUEST);
    }

    @Test
    void retriesOptimisticVersionConflict() {
        PickupCommandResult expected = new PickupCommandResult(201, null);
        given(reservationService.create(11L, KEY, CREATE_REQUEST))
                .willThrow(new ObjectOptimisticLockingFailureException(
                        PickupReservation.class, 77L))
                .willReturn(expected);
        List<Long> delays = new ArrayList<>();
        PickupCommandFacade facade = facade(delays);

        assertThat(facade.create(11L, KEY, CREATE_REQUEST)).isSameAs(expected);
        assertThat(delays).containsExactly(100L);
        then(reservationService).should(times(2)).create(11L, KEY, CREATE_REQUEST);
    }

    @Test
    void mapsThirdMysqlLockTimeoutToCommon008() {
        CannotAcquireLockException failure = mysqlLockFailure(1205);
        given(reservationService.create(11L, KEY, CREATE_REQUEST)).willThrow(failure);
        PickupCommandFacade facade = facade(new ArrayList<>());

        assertThatThrownBy(() -> facade.create(11L, KEY, CREATE_REQUEST))
                .isInstanceOfSatisfying(ServiceException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
                    assertThat(exception.getCause()).isSameAs(failure);
                });
        then(reservationService).should(times(3)).create(11L, KEY, CREATE_REQUEST);
    }

    @ParameterizedTest
    @MethodSource("nonRetryableFailures")
    void propagatesBusinessAndNonApprovedTechnicalFailuresWithoutRetry(
            RuntimeException failure
    ) {
        given(reservationService.create(11L, KEY, CREATE_REQUEST)).willThrow(failure);
        List<Long> delays = new ArrayList<>();
        PickupCommandFacade facade = facade(delays);

        assertThatThrownBy(() -> facade.create(11L, KEY, CREATE_REQUEST))
                .isSameAs(failure);
        assertThat(delays).isEmpty();
        then(reservationService).should().create(11L, KEY, CREATE_REQUEST);
    }

    @Test
    void interruptedRetrySleepRestoresInterruptAndMapsToCommon008() {
        CannotAcquireLockException failure = mysqlLockFailure(1213);
        InterruptedException interrupted = new InterruptedException("interrupted");
        given(reservationService.create(11L, KEY, CREATE_REQUEST)).willThrow(failure);
        PickupCommandFacade facade = new PickupCommandFacade(
                reservationService, managementService, ignored -> 100L,
                ignored -> { throw interrupted; });

        try {
            assertThatThrownBy(() -> facade.create(11L, KEY, CREATE_REQUEST))
                    .isInstanceOfSatisfying(ServiceException.class, exception -> {
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
                        assertThat(exception.getCause()).isSameAs(interrupted);
                    });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(interrupted.getSuppressed()).containsExactly(failure);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void delegatesConsumerAndOperatorStateCommands() {
        PickupCommandResult expected = new PickupCommandResult(200, null);
        PickupCancellationRequest consumerRequest = new PickupCancellationRequest("일정 변경");
        StorePickupCancellationRequest operatorRequest =
                new StorePickupCancellationRequest("재료 소진");
        given(reservationService.cancelByConsumer(11L, 77L, KEY, consumerRequest))
                .willReturn(expected);
        given(managementService.cancel(31L, 22L, 77L, KEY, operatorRequest))
                .willReturn(expected);
        given(managementService.fulfill(31L, 22L, 77L, KEY)).willReturn(expected);
        PickupCommandFacade facade = facade(new ArrayList<>());

        assertThat(facade.cancelByConsumer(11L, 77L, KEY, consumerRequest))
                .isSameAs(expected);
        assertThat(facade.cancelByOperator(31L, 22L, 77L, KEY, operatorRequest))
                .isSameAs(expected);
        assertThat(facade.fulfill(31L, 22L, 77L, KEY)).isSameAs(expected);
    }

    @Test
    void productionRetryDelaysStayInsideApprovedRanges() {
        assertThat(IntStream.range(0, 100)
                .mapToObj(ignored -> PickupCommandFacade.defaultDelayMillis(1)))
                .allSatisfy(delay -> assertThat(delay).isBetween(100L, 200L));
        assertThat(IntStream.range(0, 100)
                .mapToObj(ignored -> PickupCommandFacade.defaultDelayMillis(2)))
                .allSatisfy(delay -> assertThat(delay).isBetween(300L, 500L));
    }

    private PickupCommandFacade facade(List<Long> delays) {
        return new PickupCommandFacade(
                reservationService, managementService,
                attempt -> attempt == 1 ? 100L : 300L, delays::add);
    }

    private static Stream<RuntimeException> nonRetryableFailures() {
        return Stream.of(
                new ServiceException(PickupErrorCode.INSUFFICIENT_QUANTITY),
                new IllegalArgumentException("invalid request"),
                new QueryTimeoutException("query timeout"),
                new DataIntegrityViolationException(
                        "constraint", new SQLException("duplicate", "23000", 1062)),
                new CannotAcquireLockException("no vendor code")
        );
    }

    private static CannotAcquireLockException mysqlLockFailure(int errorCode) {
        return new CannotAcquireLockException(
                "database lock failure",
                new SQLException("mysql lock failure", "40001", errorCode));
    }
}
