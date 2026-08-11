package com.miriyum.domain.payment.service;

import com.miriyum.domain.payment.adapter.portone.PortOneWebhookVerifier;
import com.miriyum.domain.payment.adapter.portone.PortOneWebhookVerifier.VerifiedWebhook;
import com.miriyum.domain.payment.config.PaymentSettings;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentStatus;
import com.miriyum.domain.payment.entity.PaymentWebhookReceipt;
import com.miriyum.domain.payment.entity.PaymentWebhookReceipt.Outcome;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.domain.payment.repository.PaymentWebhookReceiptRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 서명 검증·중복 제거 후 Webhook을 Payment의 서버 조회 확정 경로로 수렴시킨다. */
@Service
public class PaymentWebhookService {

    private static final Duration PROCESSING_LEASE = Duration.ofMinutes(5);

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "Transaction.Paid",
            "Transaction.Failed",
            "Transaction.PayPending",
            "Transaction.PartialCancelled",
            "Transaction.Cancelled",
            "Transaction.CancelPending"
    );

    public enum WebhookResult { PROCESSED, IGNORED, RECONCILIATION_REQUIRED }

    private final PaymentWebhookReceiptRepository receipts;
    private final PaymentService paymentService;
    private final PaymentSettings settings;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public PaymentWebhookService(
            PaymentWebhookReceiptRepository receipts,
            PaymentService paymentService,
            PaymentSettings settings,
            Clock clock,
            ObjectMapper objectMapper
    ) {
        this.receipts = receipts;
        this.paymentService = paymentService;
        this.settings = settings;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public WebhookResult handle(
            String rawBody,
            String messageId,
            String messageTimestamp,
            String messageSignature
    ) {
        PortOneWebhookVerifier verifier = new PortOneWebhookVerifier(
                settings.getPortone().requireWebhookSecret(),
                clock,
                Duration.ofMinutes(5),
                objectMapper
        );
        VerifiedWebhook webhook = verifier.verify(
                rawBody, messageId, messageTimestamp, messageSignature);
        if (SUPPORTED_TYPES.contains(webhook.type())) {
            requireSupportedFields(webhook);
        }
        String bodySha256 = sha256(rawBody);
        PaymentWebhookReceipt existing = receipts.findById(messageId).orElse(null);
        if (existing != null) {
            if (!existing.getBodySha256().equals(bodySha256)) {
                throw new ServiceException(PaymentErrorCode.INVALID_WEBHOOK_SIGNATURE);
            }
            if (existing.getOutcome() != Outcome.RECEIVED
                    && existing.getOutcome() != Outcome.PROCESSING) {
                return mapOutcome(existing.getOutcome());
            }
        }

        Instant now = clock.instant();
        PaymentWebhookReceipt receipt = existing;
        if (receipt == null) {
            receipt = PaymentWebhookReceipt.receive(
                    messageId,
                    webhook.type(),
                    bodySha256,
                    webhook.paymentId(),
                    webhook.transactionId(),
                    webhook.cancellationId(),
                    now
            );
            try {
                receipts.saveAndFlush(receipt);
            } catch (DataIntegrityViolationException race) {
                receipt = receipts.findById(messageId).orElseThrow(() -> race);
                if (!receipt.getBodySha256().equals(bodySha256)) {
                    throw new ServiceException(PaymentErrorCode.INVALID_WEBHOOK_SIGNATURE);
                }
                if (receipt.getOutcome() != Outcome.RECEIVED
                        && receipt.getOutcome() != Outcome.PROCESSING) {
                    return mapOutcome(receipt.getOutcome());
                }
            }
        }

        if (!SUPPORTED_TYPES.contains(webhook.type())) {
            receipt.finish(Outcome.IGNORED, clock.instant());
            receipts.save(receipt);
            return WebhookResult.IGNORED;
        }
        int claimed = receipts.claimProcessing(
                messageId,
                now,
                now.minus(PROCESSING_LEASE)
        );
        if (claimed == 0) {
            return receipts.findById(messageId)
                    .map(saved -> mapOutcome(saved.getOutcome()))
                    .orElseThrow(() -> new IllegalStateException("webhook receipt disappeared"));
        }
        try {
            PaymentStatus status = paymentService.confirmWebhook(
                    webhook.paymentId(),
                    messageId,
                    webhook.type(),
                    webhook.cancellationId()
            ).status();
            if (status == PaymentStatus.RECONCILIATION_REQUIRED
                    || status == PaymentStatus.CONFIRMING) {
                receipt.finish(Outcome.RECONCILIATION_REQUIRED, clock.instant());
                receipts.save(receipt);
                return WebhookResult.RECONCILIATION_REQUIRED;
            }
            receipt.finish(Outcome.PROCESSED, clock.instant());
            receipts.save(receipt);
            return WebhookResult.PROCESSED;
        } catch (ServiceException exception) {
            if (exception.getErrorCode() == PaymentErrorCode.PAYMENT_NOT_FOUND) {
                receipt.finish(Outcome.IGNORED, clock.instant());
                receipts.save(receipt);
                return WebhookResult.IGNORED;
            }
            if (exception.getErrorCode() == PaymentErrorCode.PROVIDER_MAPPING_MISMATCH) {
                receipt.finish(Outcome.RECONCILIATION_REQUIRED, clock.instant());
                receipts.save(receipt);
                return WebhookResult.RECONCILIATION_REQUIRED;
            }
            throw exception;
        }
    }

    private void requireSupportedFields(VerifiedWebhook webhook) {
        if (!settings.getPortone().requireStoreId().equals(webhook.storeId())
                || webhook.paymentId() == null
                || webhook.transactionId() == null) {
            throw new ServiceException(CommonErrorCode.MALFORMED_REQUEST);
        }
        if ((webhook.type().equals("Transaction.PartialCancelled")
                || webhook.type().equals("Transaction.Cancelled")
                || webhook.type().equals("Transaction.CancelPending"))
                && webhook.cancellationId() == null) {
            throw new ServiceException(CommonErrorCode.MALFORMED_REQUEST);
        }
    }

    private static WebhookResult mapOutcome(Outcome outcome) {
        return switch (outcome) {
            case RECEIVED, PROCESSING ->
                    throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
            case RECONCILIATION_REQUIRED -> WebhookResult.RECONCILIATION_REQUIRED;
            case PROCESSED -> WebhookResult.PROCESSED;
            case IGNORED -> WebhookResult.IGNORED;
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
