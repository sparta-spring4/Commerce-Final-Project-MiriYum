package com.miriyum.domain.payment.adapter.portone;

import com.miriyum.domain.payment.config.PaymentSettings;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PortOne REST API V2의 결제 단건 조회와 취소만 구현하는 adapter다. */
@Component
public class PortOnePaymentClient implements PaymentProviderClient {

    private final RestClient restClient;
    private final PaymentSettings settings;
    private final ObjectMapper objectMapper;

    public PortOnePaymentClient(
            @Qualifier("portOneRestClientBuilder") RestClient.Builder restClientBuilder,
            PaymentSettings settings,
            ObjectMapper objectMapper
    ) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder
                .baseUrl(settings.getPortone().getBaseUrl())
                .build();
    }

    @Override
    public ProviderPayment getPayment(String portOnePaymentId) {
        try {
            String body = restClient.get()
                    .uri("/payments/{paymentId}", portOnePaymentId)
                    .header(HttpHeaders.AUTHORIZATION,
                            "PortOne " + settings.getPortone().requireApiSecret())
                    .retrieve()
                    .body(String.class);
            JsonNode payment = objectMapper.readTree(body);
            return new ProviderPayment(
                    requiredText(payment, "id"),
                    optionalText(payment, "transactionId"),
                    mapPaymentStatus(requiredText(payment, "status")),
                    requiredLong(payment.path("amount"), "total"),
                    requiredText(payment, "currency")
            );
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new ProviderUnavailableException("PortOne payment lookup was inconclusive", exception);
        }
    }

    @Override
    public ProviderCancellation cancelPayment(
            String portOnePaymentId,
            String refundId,
            long amountMinor,
            String currency,
            String reason
    ) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("storeId", settings.getPortone().requireStoreId());
            request.put("amount", amountMinor);
            request.put("reason", reason);
            request.put("requester", "CUSTOMER");
            String body = restClient.post()
                    .uri("/payments/{paymentId}/cancel", portOnePaymentId)
                    .header(HttpHeaders.AUTHORIZATION,
                            "PortOne " + settings.getPortone().requireApiSecret())
                    .header("Idempotency-Key", "\"" + refundId + "\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            JsonNode cancellation = objectMapper.readTree(body).path("cancellation");
            String status = requiredText(cancellation, "status");
            ProviderStatus mappedStatus = switch (status) {
                case "SUCCEEDED" -> ProviderStatus.PARTIALLY_CANCELLED;
                case "REQUESTED" -> ProviderStatus.PAY_PENDING;
                case "FAILED" -> ProviderStatus.FAILED;
                default -> ProviderStatus.UNKNOWN;
            };
            return new ProviderCancellation(
                    requiredText(cancellation, "id"),
                    mappedStatus,
                    requiredLong(cancellation, "totalAmount"),
                    currency
            );
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new ProviderUnavailableException("PortOne cancellation was inconclusive", exception);
        }
    }

    private static ProviderStatus mapPaymentStatus(String status) {
        return switch (status) {
            case "PAID" -> ProviderStatus.PAID;
            case "FAILED" -> ProviderStatus.FAILED;
            case "PAY_PENDING", "PENDING" -> ProviderStatus.PAY_PENDING;
            case "CANCELLED" -> ProviderStatus.CANCELLED;
            case "PARTIAL_CANCELLED" -> ProviderStatus.PARTIALLY_CANCELLED;
            default -> ProviderStatus.UNKNOWN;
        };
    }

    private static String requiredText(JsonNode node, String fieldName) {
        String value = optionalText(node, fieldName);
        if (value == null) {
            throw new IllegalArgumentException("PortOne response field is missing: " + fieldName);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static long requiredLong(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (!value.isIntegralNumber() || value.asLong() < 0) {
            throw new IllegalArgumentException("PortOne response field is invalid: " + fieldName);
        }
        return value.asLong();
    }
}
