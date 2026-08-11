package com.miriyum.domain.schedule.closure.entity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.schedule.closure.model.StoreClosureActorType;
import com.miriyum.domain.schedule.closure.model.TemporaryClosureReason;
import com.miriyum.domain.schedule.model.ScheduleAuditOutcome;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StoreClosureAuditEventTest {

    private static final Instant NOW = Instant.parse("2026-08-04T03:00:00Z");

    @Test
    void temporaryAuditRejectsRegularActiveVersionMetadata() {
        assertThatThrownBy(() -> StoreClosureAuditEvent.record(
                7L, StoreClosureActorType.STORE_OPERATOR, 11L,
                "TEMPORARY", "3", 1L, 2L,
                "END_CHANGED", "ACTIVE", "ACTIVE", "Asia/Seoul",
                NOW, NOW, NOW, "정비 연장", "request-1",
                ScheduleAuditOutcome.SUCCEEDED,
                NOW, NOW.plusSeconds(3600), NOW.plusSeconds(7200),
                TemporaryClosureReason.MAINTENANCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void regularAuditRejectsTemporaryClosureMetadata() {
        assertThatThrownBy(() -> StoreClosureAuditEvent.record(
                7L, StoreClosureActorType.STORE_OPERATOR, 11L,
                "REGULAR", "42", 1L, 2L,
                "ACTIVATED", "DRAFT", "ACTIVE", "Asia/Seoul",
                NOW, NOW, NOW, "게시", "request-2",
                ScheduleAuditOutcome.SUCCEEDED,
                NOW, null, NOW.plusSeconds(3600), TemporaryClosureReason.OTHER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void auditRejectsUnknownResourceType() {
        assertThatThrownBy(() -> StoreClosureAuditEvent.record(
                7L, StoreClosureActorType.SYSTEM, null,
                "UNKNOWN", "42", null, null,
                "ACTIVATION_FAILED", "SCHEDULED", "ACTIVATION_FAILED", "Asia/Seoul",
                NOW, NOW, NOW, "실패", "request-3",
                ScheduleAuditOutcome.FAILED,
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
