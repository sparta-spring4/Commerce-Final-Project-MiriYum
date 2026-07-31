package com.miriyum.domain.store.schedule.service;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.schedule.dto.OperatingHoursResponse;
import com.miriyum.domain.store.schedule.dto.ReservationTimeSlotsResponse;
import com.miriyum.domain.store.schedule.dto.WeeklyOperatingHoursRequest;
import com.miriyum.domain.store.schedule.dto.WeeklyReservationTimeSlotsRequest;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;

@Service
@RequiredArgsConstructor
public class StoreScheduleCommandFacade {

    private static final List<String> SCHEDULE_CONSTRAINT_MARKERS = List.of(
            "operating_schedule",
            "reservation_schedule",
            "store_schedule_state");

    private final StoreScheduleService scheduleService;

    public ScheduleCommandResult<OperatingHoursResponse> replaceOperatingHours(
            long operatorId,
            long storeId,
            IdempotencyKey key,
            WeeklyOperatingHoursRequest request
    ) {
        return translateConflict(() -> scheduleService.replaceOperatingHours(
                operatorId,
                storeId,
                key,
                request));
    }

    public ScheduleCommandResult<ReservationTimeSlotsResponse>
            replaceReservationTimeSlots(
                    long operatorId,
                    long storeId,
                    IdempotencyKey key,
                    WeeklyReservationTimeSlotsRequest request
            ) {
        return translateConflict(() ->
                scheduleService.replaceReservationTimeSlots(
                        operatorId,
                        storeId,
                        key,
                        request));
    }

    private <T> T translateConflict(Supplier<T> command) {
        try {
            return command.get();
        } catch (DataIntegrityViolationException exception) {
            if (containsScheduleConstraint(exception)) {
                throw scheduleConflict(exception);
            }
            throw exception;
        } catch (ConcurrencyFailureException
                 | QueryTimeoutException
                 | TransactionTimedOutException exception) {
            throw scheduleConflict(exception);
        }
    }

    private boolean containsScheduleConstraint(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && SCHEDULE_CONSTRAINT_MARKERS.stream()
                    .anyMatch(message::contains)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ServiceException scheduleConflict(RuntimeException cause) {
        ServiceException conflict =
                new ServiceException(StoreErrorCode.SCHEDULE_CONFLICT);
        conflict.initCause(cause);
        return conflict;
    }
}
