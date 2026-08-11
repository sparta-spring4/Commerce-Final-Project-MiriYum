package com.miriyum.domain.payment.adapter.portone;

import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PortOne V2 raw Webhook 요청을 Standard Webhooks 규약으로 검증한다. */
public final class PortOneWebhookVerifier {

    private static final String SECRET_PREFIX = "whsec_";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final Clock clock;
    private final Duration tolerance;
    private final ObjectMapper objectMapper;

    public PortOneWebhookVerifier(
            String webhookSecret,
            Clock clock,
            Duration tolerance,
            ObjectMapper objectMapper
    ) {
        if (webhookSecret == null || !webhookSecret.startsWith(SECRET_PREFIX)) {
            throw new IllegalArgumentException("PortOne webhook secret must start with whsec_");
        }
        try {
            this.secret = Base64.getDecoder().decode(webhookSecret.substring(SECRET_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("PortOne webhook secret must contain base64", exception);
        }
        if (secret.length == 0) {
            throw new IllegalArgumentException("PortOne webhook secret must not be empty");
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.tolerance = Objects.requireNonNull(tolerance, "tolerance must not be null");
        if (tolerance.isNegative() || tolerance.isZero()) {
            throw new IllegalArgumentException("tolerance must be positive");
        }
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public VerifiedWebhook verify(
            String rawBody,
            String messageId,
            String messageTimestamp,
            String messageSignature
    ) {
        try {
            requireText(rawBody);
            requireText(messageId);
            requireText(messageTimestamp);
            requireText(messageSignature);
            long timestampSeconds = Long.parseLong(messageTimestamp);
            Instant signedAt = Instant.ofEpochSecond(timestampSeconds);
            Duration age = Duration.between(signedAt, clock.instant()).abs();
            if (age.compareTo(tolerance) > 0) {
                throw invalidWebhook();
            }
            String signedContent = messageId + "." + messageTimestamp + "." + rawBody;
            byte[] expected = hmac(signedContent);
            boolean verified = false;
            for (String candidate : messageSignature.trim().split("\\s+")) {
                String[] parts = candidate.split(",", 2);
                if (parts.length != 2 || !"v1".equals(parts[0])) {
                    continue;
                }
                try {
                    if (MessageDigest.isEqual(expected, Base64.getDecoder().decode(parts[1]))) {
                        verified = true;
                    }
                } catch (IllegalArgumentException ignored) {
                    // 다른 rotation signature가 유효할 수 있으므로 나머지 후보를 계속 확인한다.
                }
            }
            if (!verified) {
                throw invalidWebhook();
            }

            JsonNode root = objectMapper.readTree(rawBody);
            String type = requiredText(root, "type");
            Instant.parse(requiredText(root, "timestamp"));
            JsonNode data = root.path("data");
            if (!data.isObject()) {
                throw invalidWebhook();
            }
            return new VerifiedWebhook(
                    type,
                    optionalText(data, "storeId"),
                    optionalText(data, "paymentId"),
                    optionalText(data, "transactionId"),
                    optionalText(data, "cancellationId")
            );
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidWebhook();
        }
    }

    private byte[] hmac(String signedContent) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
        return mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
    }

    private static String requiredText(JsonNode node, String fieldName) {
        String value = optionalText(node, fieldName);
        if (value == null) {
            throw invalidWebhook();
        }
        return value;
    }

    private static String optionalText(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw invalidWebhook();
        }
    }

    private static ServiceException invalidWebhook() {
        return new ServiceException(PaymentErrorCode.INVALID_WEBHOOK_SIGNATURE);
    }

    public record VerifiedWebhook(
            String type,
            String storeId,
            String paymentId,
            String transactionId,
            String cancellationId
    ) {
    }
}
