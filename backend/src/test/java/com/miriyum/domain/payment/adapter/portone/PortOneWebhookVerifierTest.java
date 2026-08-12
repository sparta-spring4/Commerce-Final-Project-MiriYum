package com.miriyum.domain.payment.adapter.portone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PortOneWebhookVerifierTest {

    private static final String BODY = """
            {"type":"Transaction.Paid","timestamp":"2026-08-11T01:00:00Z","data":{"storeId":"store-1","paymentId":"payment-reservation-900000000000000001","transactionId":"transaction-1"}}""";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T01:00:00Z"),
            ZoneOffset.UTC
    );

    private final PortOneWebhookVerifier verifier = new PortOneWebhookVerifier(
            "whsec_dGVzdC1zZWNyZXQ=",
            Clock.fixed(Instant.ofEpochSecond(1_786_410_000L), ZoneOffset.UTC),
            Duration.ofMinutes(5),
            new ObjectMapper()
    );

    @Test
    @DisplayName("raw body와 Standard Webhooks 세 헤더의 올바른 서명을 검증한다")
    void verifiesStandardWebhookSignature() {
        PortOneWebhookVerifier.VerifiedWebhook webhook = verifier.verify(
                BODY,
                "msg_1",
                "1786410000",
                "v1,hO44PjqE0JRHGBakXj3mCBbfKfbTlbRCoymiO5zrQiY="
        );

        assertThat(webhook.type()).isEqualTo("Transaction.Paid");
        assertThat(webhook.storeId()).isEqualTo("store-1");
        assertThat(webhook.paymentId())
                .isEqualTo("payment-reservation-900000000000000001");
        assertThat(webhook.transactionId()).isEqualTo("transaction-1");
        assertThat(webhook.cancellationId()).isNull();
    }

    @Test
    @DisplayName("서명된 알 수 없는 type은 검증하되 결제 식별자를 요구하지 않는다")
    void acceptsSignedUnknownTypeForIgnorePath() {
        String body = "{\"type\":\"BillingKey.Issued\",\"timestamp\":\"2026-08-11T01:00:00Z\",\"data\":{\"storeId\":\"store-1\"}}";

        PortOneWebhookVerifier.VerifiedWebhook webhook = verifier.verify(
                body,
                "msg_2",
                "1786410000",
                "v1,TesUxk29USDJxkeJwmJL9nH5N3jCrDkHW0GBJAKiUWQ="
        );

        assertThat(webhook.type()).isEqualTo("BillingKey.Issued");
        assertThat(webhook.paymentId()).isNull();
    }

    @Test
    @DisplayName("본문 또는 서명이 바뀌면 PAYMENT_006으로 거부한다")
    void rejectsInvalidSignature() {
        assertInvalidWebhook(() -> verifier.verify(
                BODY + " ",
                "msg_1",
                "1786410000",
                "v1,hO44PjqE0JRHGBakXj3mCBbfKfbTlbRCoymiO5zrQiY="
        ));
    }

    @Test
    @DisplayName("허용 오차보다 오래된 서명은 재생 공격으로 거부한다")
    void rejectsStaleTimestamp() {
        PortOneWebhookVerifier staleVerifier = new PortOneWebhookVerifier(
                "whsec_dGVzdC1zZWNyZXQ=",
                Clock.offset(CLOCK, Duration.ofMinutes(6)),
                Duration.ofMinutes(5),
                new ObjectMapper()
        );

        assertInvalidWebhook(() -> staleVerifier.verify(
                BODY,
                "msg_1",
                "1786410000",
                "v1,hO44PjqE0JRHGBakXj3mCBbfKfbTlbRCoymiO5zrQiY="
        ));
    }

    @Test
    @DisplayName("서명이 맞아도 본문 timestamp가 누락되거나 ISO-8601이 아니면 거부한다")
    void rejectsMalformedEnvelopeTimestamp() throws Exception {
        String body = """
                {"type":"Transaction.Paid","timestamp":"not-an-instant","data":{"storeId":"store-1","paymentId":"payment-reservation-900000000000000001","transactionId":"transaction-1"}}""";
        String messageId = "msg_invalid_body_timestamp";
        String timestamp = "1786410000";

        assertInvalidWebhook(() -> verifier.verify(
                body,
                messageId,
                timestamp,
                "v1," + signature(messageId, timestamp, body)
        ));
    }

    private static String signature(String messageId, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    Base64.getDecoder().decode("dGVzdC1zZWNyZXQ="),
                    "HmacSHA256"
            ));
            return Base64.getEncoder().encodeToString(mac.doFinal(
                    (messageId + "." + timestamp + "." + body)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertInvalidWebhook(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(PaymentErrorCode.INVALID_WEBHOOK_SIGNATURE);
    }
}
