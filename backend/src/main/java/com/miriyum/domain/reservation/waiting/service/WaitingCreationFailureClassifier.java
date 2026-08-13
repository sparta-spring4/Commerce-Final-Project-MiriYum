package com.miriyum.domain.reservation.waiting.service;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

final class WaitingCreationFailureClassifier {
    static final String MEMBERSHIP_UNIQUE = "uk_waiting_active_memberships_store_consumer";
    static final String SEQUENCE_BOOTSTRAP_UNIQUE = "PRIMARY";
    private WaitingCreationFailureClassifier() {}

    static boolean isMembershipConflict(Throwable failure) {
        return contains(failure, MEMBERSHIP_UNIQUE);
    }

    static boolean isRetryable(Throwable failure) {
        if (failure instanceof DataIntegrityViolationException)
            return contains(failure, "waiting_queue_sequences") && contains(failure, SEQUENCE_BOOTSTRAP_UNIQUE);
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof QueryTimeoutException || current instanceof TransactionTimedOutException) return true;
            if (current instanceof SQLException sql && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205)) return true;
        }
        return false;
    }

    private static boolean contains(Throwable failure, String token) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current.getMessage() != null && current.getMessage().contains(token)) return true;
        return false;
    }
}
