package com.miriyum.global.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SseCursorCodecTest {

    private static final long WATERMARK = 9_000_000_000_000_123L;

    @Test
    void roundTripsUrlSafeCursorBoundToEveryScopeField() {
        SseCursorCodec codec = codec();
        SseStreamScope scope = SseStreamScope.waitingStoreOperator(41L, 103L);

        String cursor = codec.encode(scope, WATERMARK);

        assertThat(cursor).matches("^[A-Za-z0-9_-]+$").hasSizeLessThanOrEqualTo(512);
        assertThat(codec.decode(scope, cursor)).isEqualTo(WATERMARK);
    }

    @Test
    void rejectsTamperingAndCrossScopeReuseAsCommonValidationError() {
        SseCursorCodec codec = codec();
        SseStreamScope owner = SseStreamScope.waitingStoreOperator(41L, 103L);
        String cursor = codec.encode(owner, WATERMARK);
        int middle = cursor.length() / 2;
        char changed = cursor.charAt(middle) == 'A' ? 'B' : 'A';

        assertInvalid(() -> codec.decode(owner,
                cursor.substring(0, middle) + changed + cursor.substring(middle + 1)));
        assertInvalid(() -> codec.decode(SseStreamScope.waitingStoreOperator(42L, 103L), cursor));
        assertInvalid(() -> codec.decode(SseStreamScope.waitingStoreOperator(41L, 104L), cursor));
        assertInvalid(() -> codec.decode(SseStreamScope.notificationConsumer(41L), cursor));
    }

    @Test
    void rejectsMalformedCursorShapesAndNegativeWatermark() {
        SseCursorCodec codec = codec();
        SseStreamScope scope = SseStreamScope.notificationConsumer(41L);

        assertInvalid(() -> codec.decode(scope, ""));
        assertInvalid(() -> codec.decode(scope, "A".repeat(513)));
        assertInvalid(() -> codec.decode(scope, "contains.dot"));
        assertThatThrownBy(() -> codec.encode(scope, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsStableOpaqueRoutingKeysWithoutRawScopeValues() {
        SseCursorCodec codec = codec();
        SseWakeUpTarget target = SseWakeUpTarget.waitingStoreDate(
                103L, LocalDate.of(2026, 8, 19));

        String key = codec.routingKey(target);

        assertThat(key).matches("^[0-9a-f]{64}$")
                .doesNotContain("103", "2026-08-19");
        assertThat(codec.routingKey(target)).isEqualTo(key);
    }

    private static SseCursorCodec codec() {
        return new SseCursorCodec(new SseRuntimeProperties(
                true, "0123456789abcdef0123456789abcdef",
                Duration.ofMinutes(1), Duration.ofSeconds(15), Duration.ofSeconds(5),
                20, 100, 5));
    }

    private static void assertInvalid(ThrowingCall call) {
        assertThatThrownBy(call::invoke)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void invoke();
    }
}
