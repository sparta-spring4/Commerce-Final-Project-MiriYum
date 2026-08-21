package com.miriyum.domain.reservation.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.config.WaitingHistoryProperties;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerHistoryContracts.Scope;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WaitingConsumerHistoryCursorCodecTest {

    private final WaitingConsumerHistoryCursorCodec codec =
            new WaitingConsumerHistoryCursorCodec(new WaitingHistoryProperties(
                    "test-waiting-history-cursor-secret-with-enough-entropy"));

    @Test
    void restoresSignedBoundaryForSameConsumerAndScope() {
        WaitingConsumerHistoryCursorCodec.Boundary boundary =
                new WaitingConsumerHistoryCursorCodec.Boundary(
                        Instant.parse("2026-08-20T03:04:05.123456Z"), 301L);

        String cursor = codec.encode(200L, Scope.TERMINAL, boundary);

        assertThat(codec.decode(200L, Scope.TERMINAL, cursor)).isEqualTo(boundary);
        assertThat(cursor).matches("^[A-Za-z0-9_-]{1,512}$");
    }

    @Test
    void rejectsTamperedCursor() {
        String cursor = codec.encode(
                200L,
                Scope.ALL,
                new WaitingConsumerHistoryCursorCodec.Boundary(
                        Instant.parse("2026-08-20T03:04:05Z"), 301L));
        String tampered = cursor.substring(0, cursor.length() - 1)
                + (cursor.endsWith("A") ? "B" : "A");

        assertInvalidCursor(() -> codec.decode(200L, Scope.ALL, tampered));
    }

    @Test
    void rejectsCursorBoundToAnotherConsumer() {
        String cursor = codec.encode(
                200L,
                Scope.CURRENT,
                new WaitingConsumerHistoryCursorCodec.Boundary(
                        Instant.parse("2026-08-20T03:04:05Z"), 301L));

        assertInvalidCursor(() -> codec.decode(201L, Scope.CURRENT, cursor));
    }

    @Test
    void rejectsCursorBoundToAnotherScope() {
        String cursor = codec.encode(
                200L,
                Scope.CURRENT,
                new WaitingConsumerHistoryCursorCodec.Boundary(
                        Instant.parse("2026-08-20T03:04:05Z"), 301L));

        assertInvalidCursor(() -> codec.decode(200L, Scope.TERMINAL, cursor));
    }

    private static void assertInvalidCursor(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.WAITING_HISTORY_CURSOR_INVALID));
    }
}
