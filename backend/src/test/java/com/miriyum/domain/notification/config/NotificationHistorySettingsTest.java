package com.miriyum.domain.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.junit.jupiter.api.Test;

class NotificationHistorySettingsTest {

    @Test
    void acceptsDedicatedCursorSecretWithAtLeastThirtyTwoCharacters() {
        NotificationHistorySettings settings = new NotificationHistorySettings(
                "0123456789abcdef0123456789abcdef"
        );

        assertThat(settings.requireCursorKey()).hasSize(32);
    }

    @Test
    void missingOrShortCursorSecretFailsClosed() {
        assertUnavailable(new NotificationHistorySettings(null));
        assertUnavailable(new NotificationHistorySettings("short-secret"));
    }

    private static void assertUnavailable(NotificationHistorySettings settings) {
        assertThatThrownBy(settings::requireCursorKey)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }
}
