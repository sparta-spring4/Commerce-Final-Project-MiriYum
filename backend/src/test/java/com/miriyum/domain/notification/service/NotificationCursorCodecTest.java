package com.miriyum.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.notification.config.NotificationHistorySettings;
import com.miriyum.domain.notification.exception.NotificationErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationCursorCodecTest {

    private static final long CONSUMER_ID = 41L;
    private static final NotificationCursorCodec.Boundary BOUNDARY =
            new NotificationCursorCodec.Boundary(
                    Instant.parse("2026-08-13T03:04:05.123456Z"),
                    900000000000000123L
            );

    @Test
    void roundTripsVersionedSortBoundaryAsOpaqueUrlSafeValue() {
        NotificationCursorCodec codec = codec("0123456789abcdef0123456789abcdef");

        String cursor = codec.encode(CONSUMER_ID, BOUNDARY);

        assertThat(cursor).matches("^[A-Za-z0-9_-]+$").hasSizeLessThanOrEqualTo(512);
        assertThat(codec.decode(CONSUMER_ID, cursor)).isEqualTo(BOUNDARY);
    }

    @Test
    void rejectsTamperedCursor() {
        NotificationCursorCodec codec = codec("0123456789abcdef0123456789abcdef");
        String cursor = codec.encode(CONSUMER_ID, BOUNDARY);
        int index = cursor.length() / 2;
        char replacement = cursor.charAt(index) == 'A' ? 'B' : 'A';
        String tampered = cursor.substring(0, index) + replacement + cursor.substring(index + 1);

        assertInvalidCursor(() -> codec.decode(CONSUMER_ID, tampered));
    }

    @Test
    void rejectsCursorIssuedForAnotherConsumer() {
        NotificationCursorCodec codec = codec("0123456789abcdef0123456789abcdef");
        String cursor = codec.encode(CONSUMER_ID, BOUNDARY);

        assertInvalidCursor(() -> codec.decode(CONSUMER_ID + 1, cursor));
    }

    @Test
    void rejectsUnknownContractVersionEvenWhenSignatureIsValid() {
        NotificationCursorCodec codec = codec("0123456789abcdef0123456789abcdef");
        String cursor = codec.encodeForTest("notification-history-v2", CONSUMER_ID, BOUNDARY);

        assertInvalidCursor(() -> codec.decode(CONSUMER_ID, cursor));
    }

    @Test
    void rejectsSignedButOutOfRangeTimestampAsInvalidCursor() {
        NotificationCursorCodec codec = codec("0123456789abcdef0123456789abcdef");
        String cursor = codec.encodeRawForTest(
                "notification-history-v1\n9223372036854775807\n0\n103",
                CONSUMER_ID
        );

        assertInvalidCursor(() -> codec.decode(CONSUMER_ID, cursor));
    }

    @Test
    void missingDedicatedSecretDoesNotFallBackToAnotherApplicationSecret() {
        NotificationCursorCodec codec = codec("");

        assertThatThrownBy(() -> codec.encode(CONSUMER_ID, BOUNDARY))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }

    private static NotificationCursorCodec codec(String secret) {
        return new NotificationCursorCodec(new NotificationHistorySettings(secret));
    }

    private static void assertInvalidCursor(ThrowingCall call) {
        assertThatThrownBy(call::invoke)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(NotificationErrorCode.INVALID_HISTORY_CURSOR));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void invoke();
    }
}
