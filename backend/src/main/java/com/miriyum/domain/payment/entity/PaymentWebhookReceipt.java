package com.miriyum.domain.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Webhook 원문 대신 식별자와 해시만 저장하는 중복 수신 기록이다. */
@Entity
@Table(name = "payment_webhook_receipts")
public class PaymentWebhookReceipt {

    public enum Outcome { RECEIVED, PROCESSING, PROCESSED, IGNORED, RECONCILIATION_REQUIRED }

    @Id
    @Column(name = "webhook_message_id", length = 255)
    private String messageId;

    @Column(name = "event_type", nullable = false, length = 255)
    private String eventType;

    @Column(name = "body_sha256", nullable = false, length = 64)
    private String bodySha256;

    @Column(name = "portone_payment_id", length = 64)
    private String portOnePaymentId;

    @Column(name = "provider_transaction_id", length = 255)
    private String providerTransactionId;

    @Column(name = "provider_cancellation_id", length = 255)
    private String providerCancellationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private Outcome outcome;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected PaymentWebhookReceipt() {
    }

    public static PaymentWebhookReceipt receive(
            String messageId,
            String eventType,
            String bodySha256,
            String portOnePaymentId,
            String providerTransactionId,
            String providerCancellationId,
            Instant receivedAt
    ) {
        PaymentWebhookReceipt receipt = new PaymentWebhookReceipt();
        receipt.messageId = messageId;
        receipt.eventType = eventType;
        receipt.bodySha256 = bodySha256;
        receipt.portOnePaymentId = portOnePaymentId;
        receipt.providerTransactionId = providerTransactionId;
        receipt.providerCancellationId = providerCancellationId;
        receipt.outcome = Outcome.RECEIVED;
        receipt.receivedAt = receivedAt;
        return receipt;
    }

    public void finish(Outcome outcome, Instant processedAt) {
        this.outcome = outcome;
        this.processedAt = processedAt;
    }

    public String getBodySha256() {
        return bodySha256;
    }

    public Outcome getOutcome() {
        return outcome;
    }
}
