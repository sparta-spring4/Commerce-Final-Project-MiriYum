package com.miriyum.domain.reservation.waiting.service;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Component;

@Component
public final class WaitingAutoOpenFailureClassifier {

    public Decision classify(Throwable failure) {
        if (hasCause(failure, CannotAcquireLockException.class)
                || hasCause(failure, PessimisticLockingFailureException.class)) {
            return new Decision(true, "DB_LOCK_TRANSIENT");
        }
        if (hasCause(failure, TransientDataAccessResourceException.class)) {
            return new Decision(true, "DB_CONNECTION_TRANSIENT");
        }
        if (hasCause(failure, DataIntegrityViolationException.class)) {
            return new Decision(false, "DATA_INTEGRITY");
        }
        return new Decision(false, "UNEXPECTED");
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public record Decision(boolean retryable, String code) {
    }
}
