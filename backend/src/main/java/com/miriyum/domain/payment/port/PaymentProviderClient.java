package com.miriyum.domain.payment.port;

/** Payment core가 소비하는 단일 PG(PortOne V2) 추상 경계다. */
public interface PaymentProviderClient {

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
            String currency
    ) {
    }

    record ProviderCancellation(
            String cancellationId,
            ProviderStatus status,
            long amountMinor,
            String currency
    ) {
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
