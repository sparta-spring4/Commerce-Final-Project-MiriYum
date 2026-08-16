package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

class WaitingSettingFailureClassifierTest {
    @Test
    void recognizesOptimisticUniqueAndDeadlockConflicts() {
        assertThat(WaitingSettingFailureClassifier.isConcurrentModification(
                new OptimisticLockingFailureException("stale"))).isTrue();
        assertThat(WaitingSettingFailureClassifier.isConcurrentModification(
                new DataIntegrityViolationException("uk_waiting_settings_store"))).isTrue();
        assertThat(WaitingSettingFailureClassifier.isConcurrentModification(
                new RuntimeException(new SQLException("deadlock", "40001", 1213)))).isTrue();
        assertThat(WaitingSettingFailureClassifier.isConcurrentModification(
                new IllegalArgumentException("other"))).isFalse();
    }
}
