package com.miriyum.domain.payment.port;

import java.util.List;

/** Payment core가 소비하는 단일 PG(PortOne V2) 추상 경계다. */
public interface PaymentProviderClient {

    static String cancellationReason(String reason, String refundId) {
        if (reason == null || reason.isBlank() || refundId == null || refundId.isBlank()) {
            throw new IllegalArgumentException("cancellation reason reference is invalid");
        }
        return reason + " [MIRIYUM_REFUND_ID=" + refundId + "]";
    }

    ProviderPayment getPayment(String portOnePaymentId);

    ProviderCancellation cancelPayment(
            String portOnePaymentId,
            String refundId,
            long amountMinor,
            String currency,
            String reason
    );

    enum ProviderStatus {
        PAID,
        FAILED,
        PAY_PENDING,
        CANCELLED,
        PARTIALLY_CANCELLED,
        UNKNOWN
    }

    record ProviderPayment(
            String portOnePaymentId,
            String transactionId,
            ProviderStatus status,
            long amountMinor,
            String currency,
            List<ProviderCancellation> cancellations
    ) {
        public ProviderPayment {
            cancellations = List.copyOf(cancellations);
        }

        public ProviderPayment(
                String portOnePaymentId,
                String transactionId,
                ProviderStatus status,
                long amountMinor,
                String currency
        ) {
            this(portOnePaymentId, transactionId, status, amountMinor, currency, List.of());
        }
    }

    record ProviderCancellation(
            String cancellationId,
            ProviderStatus status,
            long amountMinor,
            String currency,
            String reason
    ) {
        public ProviderCancellation(
                String cancellationId,
                ProviderStatus status,
                long amountMinor,
                String currency
        ) {
            this(cancellationId, status, amountMinor, currency, null);
        }
    }

    final class ProviderUnavailableException extends RuntimeException {
        public ProviderUnavailableException(String message) {
            super(message);
        }

        public ProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
