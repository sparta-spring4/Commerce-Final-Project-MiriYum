package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessResourceException;

class WaitingAutoOpenFailureClassifierTest {

    private final WaitingAutoOpenFailureClassifier classifier =
            new WaitingAutoOpenFailureClassifier();

    @Test
    void retriesOnlyKnownTransientDatabaseFailures() {
        assertThat(classifier.classify(new CannotAcquireLockException("deadlock")))
                .isEqualTo(new WaitingAutoOpenFailureClassifier.Decision(
                        true,
                        "DB_LOCK_TRANSIENT"));
        assertThat(classifier.classify(new PessimisticLockingFailureException("deadlock loser")))
                .isEqualTo(new WaitingAutoOpenFailureClassifier.Decision(
                        true,
                        "DB_LOCK_TRANSIENT"));
        assertThat(classifier.classify(
                new TransientDataAccessResourceException("connection lost")))
                .isEqualTo(new WaitingAutoOpenFailureClassifier.Decision(
                        true,
                        "DB_CONNECTION_TRANSIENT"));
    }

    @Test
    void quarantinesIntegrityAndUnknownFailures() {
        assertThat(classifier.classify(new DataIntegrityViolationException("constraint")))
                .isEqualTo(new WaitingAutoOpenFailureClassifier.Decision(
                        false,
                        "DATA_INTEGRITY"));
        assertThat(classifier.classify(new IllegalStateException("unexpected")))
                .isEqualTo(new WaitingAutoOpenFailureClassifier.Decision(
                        false,
                        "UNEXPECTED"));
    }
}
