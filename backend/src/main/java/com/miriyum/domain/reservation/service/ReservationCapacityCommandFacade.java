package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.dto.request.ReservationCapacitiesRequest;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntToLongFunction;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class ReservationCapacityCommandFacade {

    private static final int MAX_ATTEMPTS = 3;
    private static final List<String> CAPACITY_CONSTRAINT_MARKERS = List.of(
            "uk_reservation_capacity_buckets_business_key",
            "ck_reservation_capacity_buckets_service_time",
            "ck_reservation_capacity_buckets_capacity",
            "ck_reservation_capacity_buckets_party_size",
            "ck_reservation_capacity_buckets_policy_version"
    );

    private final ReservationCapacityPublicationService publicationService;
    private final IntToLongFunction retryDelayMillis;
    private final RetrySleeper retrySleeper;

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public ReservationCapacityCommandFacade(
            ReservationCapacityPublicationService publicationService
    ) {
        this(
                publicationService,
                ReservationCapacityCommandFacade::defaultDelayMillis,
                Thread::sleep
        );
    }

    ReservationCapacityCommandFacade(
            ReservationCapacityPublicationService publicationService,
            IntToLongFunction retryDelayMillis,
            RetrySleeper retrySleeper
    ) {
        this.publicationService = publicationService;
        this.retryDelayMillis = retryDelayMillis;
        this.retrySleeper = retrySleeper;
    }

    public ReservationCapacityCommandResult replace(
            long operatorId,
            long storeId,
            LocalDate serviceDate,
            IdempotencyKey key,
            ReservationCapacitiesRequest request
    ) {
        return executeWithRetry(() -> publicationService.replaceCapacities(
                operatorId,
                storeId,
                serviceDate,
                key,
                request
        ));
    }

    private <T> T executeWithRetry(Supplier<T> command) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return command.get();
            } catch (DataIntegrityViolationException exception) {
                if (containsCapacityConstraint(exception)) {
                    throw capacityConflict(exception);
                }
                throw exception;
            } catch (ConcurrencyFailureException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw concurrentModification(exception);
                }
                sleepBeforeRetry(attempt, exception);
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }

    private void sleepBeforeRetry(int failedAttempt, RuntimeException failure) {
        try {
            retrySleeper.sleep(retryDelayMillis.applyAsLong(failedAttempt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            exception.addSuppressed(failure);
            throw concurrentModification(exception);
        }
    }

    private static long defaultDelayMillis(int failedAttempt) {
        return switch (failedAttempt) {
            case 1 -> ThreadLocalRandom.current().nextLong(100L, 201L);
            case 2 -> ThreadLocalRandom.current().nextLong(300L, 501L);
            default -> throw new IllegalArgumentException("unsupported retry attempt");
        };
    }

    private static ServiceException concurrentModification(Throwable cause) {
        ServiceException conflict = new ServiceException(
                CommonErrorCode.CONCURRENT_MODIFICATION
        );
        conflict.initCause(cause);
        return conflict;
    }

    private static boolean containsCapacityConstraint(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && CAPACITY_CONSTRAINT_MARKERS.stream()
                    .anyMatch(message::contains)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static ServiceException capacityConflict(Throwable cause) {
        ServiceException conflict = new ServiceException(
                ReservationErrorCode.CAPACITY_CONFIGURATION_CONFLICT
        );
        conflict.initCause(cause);
        return conflict;
    }
}
