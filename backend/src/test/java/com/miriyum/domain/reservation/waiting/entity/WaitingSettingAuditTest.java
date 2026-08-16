package com.miriyum.domain.reservation.waiting.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WaitingSettingAuditTest {

    @Test
    void snapshotsTheCommittedPublicVersionAndActor() {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        WaitingSetting setting = WaitingSetting.create(
                9L, false, WaitingReceptionMode.PAUSED, 60, now);

        WaitingSettingAudit audit = WaitingSettingAudit.record(setting, 101L, now);

        assertThat(audit.getStoreId()).isEqualTo(9L);
        assertThat(audit.getSettingsVersion()).isEqualTo(1L);
        assertThat(audit.getOperatorAccountId()).isEqualTo(101L);
        assertThat(audit.getReceptionMode()).isEqualTo(WaitingReceptionMode.PAUSED);
    }
}
