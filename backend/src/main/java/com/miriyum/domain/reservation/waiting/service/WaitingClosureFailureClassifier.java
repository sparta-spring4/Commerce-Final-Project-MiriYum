package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.global.exception.ServiceException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

final class WaitingClosureFailureClassifier {
    private WaitingClosureFailureClassifier() {}

    static boolean isRetryable(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof ServiceException || current instanceof IllegalArgumentException
                    || current instanceof DataIntegrityViolationException) return false;
            if (current instanceof QueryTimeoutException || current instanceof TransactionTimedOutException) return true;
            if (current instanceof SQLException sql && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205))
                return true;
        }
        return false;
    }
}
