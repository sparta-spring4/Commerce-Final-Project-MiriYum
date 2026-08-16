package com.miriyum.domain.reservation.waiting.service;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

final class WaitingSettingFailureClassifier {
    private WaitingSettingFailureClassifier() {}

    static boolean isConcurrentModification(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof OptimisticLockingFailureException
                    || current instanceof QueryTimeoutException
                    || current instanceof TransactionTimedOutException) return true;
            if (current instanceof DataIntegrityViolationException
                    && contains(current, "uk_waiting_settings_store")) return true;
            if (current instanceof SQLException sql
                    && (sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213)) return true;
        }
        return false;
    }

    private static boolean contains(Throwable failure, String token) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(token)) return true;
        }
        return false;
    }
}
