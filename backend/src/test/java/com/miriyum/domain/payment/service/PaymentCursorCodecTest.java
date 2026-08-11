package com.miriyum.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentCursorCodecTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T01:00:00Z"),
            ZoneOffset.UTC
    );

    private final PaymentCursorCodec codec = new PaymentCursorCodec(
            "test-history-cursor-secret-with-enough-entropy",
            CLOCK
    );

    @Test
    @DisplayName("정렬 경계와 조회 필터 fingerprint를 위변조 방지 cursor로 왕복한다")
    void roundTripsSignedCursor() {
        String cursor = codec.encode(
                Instant.parse("2026-08-10T03:04:05Z"),
                "900000000000000123",
                "status=PAID"
        );

        PaymentCursorCodec.Cursor decoded = codec.decode(cursor, "status=PAID");

        assertThat(decoded.createdAt()).isEqualTo(Instant.parse("2026-08-10T03:04:05Z"));
        assertThat(decoded.paymentId()).isEqualTo("900000000000000123");
    }

    @Test
    @DisplayName("DB DATETIME(6)의 microsecond 정렬 경계를 손실하지 않는다")
    void preservesMicrosecondBoundary() {
        Instant boundary = Instant.parse("2026-08-10T03:04:05.123456Z");

        String cursor = codec.encode(boundary, "900000000000000123", "status=PAID");

        assertThat(codec.decode(cursor, "status=PAID").createdAt()).isEqualTo(boundary);
    }

    @Test
    @DisplayName("cursor 본문 한 글자라도 바뀌면 PAYMENT_005로 거부한다")
    void rejectsTamperedCursor() {
        String cursor = codec.encode(
                Instant.parse("2026-08-10T03:04:05Z"),
                "900000000000000123",
                "status=PAID"
        );
        char replacement = cursor.charAt(4) == 'A' ? 'B' : 'A';
        String tampered = cursor.substring(0, 4) + replacement + cursor.substring(5);

        assertInvalidCursor(() -> codec.decode(tampered, "status=PAID"));
    }

    @Test
    @DisplayName("다른 status 필터에서 발급한 cursor를 재사용하지 않는다")
    void rejectsCursorFromDifferentFilter() {
        String cursor = codec.encode(
                Instant.parse("2026-08-10T03:04:05Z"),
                "900000000000000123",
                "status=PAID"
        );

        assertInvalidCursor(() -> codec.decode(cursor, "status=REFUNDED"));
    }

    private static void assertInvalidCursor(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.INVALID_HISTORY_CURSOR);
    }
}
