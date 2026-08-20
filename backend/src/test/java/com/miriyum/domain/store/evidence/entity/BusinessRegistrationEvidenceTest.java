package com.miriyum.domain.store.evidence.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.store.evidence.enums.BusinessRegistrationEvidenceStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BusinessRegistrationEvidenceTest {

    @Test
    void replacementBlocksCurrentAccessAndPreservesRetentionDeadline() {
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        BusinessRegistrationEvidence evidence = BusinessRegistrationEvidence.createCurrent(
                UUID.randomUUID(), 10L, 3L, 7L, UUID.randomUUID(), now);

        evidence.replace(now.plus(1, ChronoUnit.HOURS), now.plus(7, ChronoUnit.DAYS));

        assertThat(evidence.isCurrentEvidence()).isFalse();
        assertThat(evidence.getEvidenceStatus()).isEqualTo(BusinessRegistrationEvidenceStatus.REPLACED);
        assertThat(evidence.getRetentionDueAt()).isEqualTo(now.plus(7, ChronoUnit.DAYS));
    }

    @Test
    void replacementCannotBeAppliedTwice() {
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        BusinessRegistrationEvidence evidence = BusinessRegistrationEvidence.createCurrent(
                UUID.randomUUID(), 10L, 3L, 7L, UUID.randomUUID(), now);
        evidence.replace(now, now.plus(7, ChronoUnit.DAYS));

        assertThatThrownBy(() -> evidence.replace(now, now.plus(7, ChronoUnit.DAYS)))
                .isInstanceOf(IllegalStateException.class);
    }
}
