package com.miriyum.domain.store.schedule.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.schedule.dto.WeeklyOperatingHoursRequest;
import com.miriyum.domain.store.schedule.dto.WeeklyReservationTimeSlotsRequest;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionTimedOutException;

@ExtendWith(MockitoExtension.class)
class StoreScheduleCommandFacadeTest {

    private static final long OPERATOR_ID = 11L;
    private static final long STORE_ID = 7L;
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440000");

    @Mock
    private StoreScheduleService scheduleService;

    private StoreScheduleCommandFacade facade;

    @BeforeEach
    void setUp() {
        facade = new StoreScheduleCommandFacade(scheduleService);
    }

    @Test
    void lockFailureBecomesStore006() {
        WeeklyOperatingHoursRequest request =
                new WeeklyOperatingHoursRequest(List.of());
        given(scheduleService.replaceOperatingHours(
                OPERATOR_ID, STORE_ID, KEY, request))
                .willThrow(new CannotAcquireLockException("lock timeout"));

        assertStore006(() -> facade.replaceOperatingHours(
                OPERATOR_ID, STORE_ID, KEY, request));
    }

    @Test
    void transactionTimeoutBecomesStore006() {
        WeeklyReservationTimeSlotsRequest request =
                new WeeklyReservationTimeSlotsRequest(List.of());
        given(scheduleService.replaceReservationTimeSlots(
                OPERATOR_ID, STORE_ID, KEY, request))
                .willThrow(new TransactionTimedOutException("timed out"));

        assertStore006(() -> facade.replaceReservationTimeSlots(
                OPERATOR_ID, STORE_ID, KEY, request));
    }

    @Test
    void knownScheduleConstraintBecomesStore006() {
        WeeklyOperatingHoursRequest request =
                new WeeklyOperatingHoursRequest(List.of());
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException(
                        "constraint failed",
                        new IllegalStateException(
                                "uk_operating_schedule_store_version"));
        given(scheduleService.replaceOperatingHours(
                OPERATOR_ID, STORE_ID, KEY, request))
                .willThrow(failure);

        assertStore006(() -> facade.replaceOperatingHours(
                OPERATOR_ID, STORE_ID, KEY, request));
    }

    @Test
    void unrelatedDataIntegrityFailureIsNotHidden() {
        WeeklyOperatingHoursRequest request =
                new WeeklyOperatingHoursRequest(List.of());
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("unrelated");
        given(scheduleService.replaceOperatingHours(
                OPERATOR_ID, STORE_ID, KEY, request))
                .willThrow(failure);

        assertThatThrownBy(() -> facade.replaceOperatingHours(
                OPERATOR_ID, STORE_ID, KEY, request))
                .isSameAs(failure);
    }

    private void assertStore006(Runnable command) {
        assertThatThrownBy(command::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception ->
                        ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.SCHEDULE_CONFLICT);
    }
}
