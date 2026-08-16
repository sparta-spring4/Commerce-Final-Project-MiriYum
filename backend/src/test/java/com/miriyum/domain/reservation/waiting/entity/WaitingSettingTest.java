package com.miriyum.domain.reservation.waiting.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WaitingSettingTest {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void createsTheFirstPublicVersionAndReplacesTheWholeSetting() {
        WaitingSetting setting = WaitingSetting.create(
                7L, true, WaitingReceptionMode.AUTO, 60, NOW);

        assertThat(setting.getVersion()).isEqualTo(1L);

        setting.replace(1L, true, WaitingReceptionMode.MANUAL, 30, NOW.plusSeconds(1));

        assertThat(setting.getVersion()).isEqualTo(2L);
        assertThat(setting.isEnabled()).isTrue();
        assertThat(setting.getReceptionMode()).isEqualTo(WaitingReceptionMode.MANUAL);
        assertThat(setting.getAdvanceOpenMinutes()).isEqualTo(30);
    }

    @Test
    void rejectsStaleVersionAndInvalidSettingsWithoutMutation() {
        WaitingSetting setting = WaitingSetting.create(
                7L, false, WaitingReceptionMode.PAUSED, 60, NOW);

        assertThatThrownBy(() -> setting.replace(
                0L, true, WaitingReceptionMode.AUTO, 60, NOW.plusSeconds(1)))
                .isInstanceOf(com.miriyum.global.exception.ServiceException.class)
                .extracting(e -> ((com.miriyum.global.exception.ServiceException) e).getErrorCode())
                .isEqualTo(com.miriyum.domain.reservation.exception.ReservationErrorCode.WAITING_SETTING_VERSION_CONFLICT);
        assertThatThrownBy(() -> WaitingSetting.create(
                7L, false, WaitingReceptionMode.AUTO, 60, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WaitingSetting.create(
                7L, true, WaitingReceptionMode.AUTO, 181, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(setting.getVersion()).isEqualTo(1L);
    }

}
