package com.miriyum.domain.payment.dto;

import java.util.Set;
import java.util.regex.Pattern;

/** Payment가 운영 복구 소비자에게 공개하는 scalar-only 계약이다. */
public final class PaymentRecoveryContracts {

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern MASKED_PROVIDER_REFERENCE = Pattern.compile(
            "^port\\*{8}[A-Za-z0-9_-]{1,4}$");

    private PaymentRecoveryContracts() {
    }

    public enum ManualRecoverySourceType {
        RESERVATION_DEPOSIT_DISPOSITION,
        RESERVATION_DEPOSIT_REFUND
    }

    public enum ManualRecoveryKind {
        DISPOSITION_RESULT_UNKNOWN,
        DISPOSITION_FAILED,
        REFUND_RESULT_UNKNOWN,
        REFUND_FAILED
    }

    public enum ManualRecoveryAction {
        REQUERY_PROVIDER_RESULT,
        RETRY_REFUND
    }

    public enum ManualRecoveryRegistrationStatus {
        REGISTERED,
        ALREADY_REGISTERED,
        NOT_REQUIRED
    }

    public enum ManualRecoveryResultStatus {
        UNKNOWN,
        FAILED,
        SUCCEEDED
    }

    public record RegisterManualRecoveryHandoffCommand(
            ManualRecoverySourceType sourceType,
            String sourceId,
            String paymentId,
            String sourceEventId,
            String idempotencyKey
    ) {
        public RegisterManualRecoveryHandoffCommand {
            if (sourceType == null) {
                throw new IllegalArgumentException("sourceType must not be null");
            }
            requirePublicId(sourceId, "sourceId");
            requirePublicId(paymentId, "paymentId");
            requireText(sourceEventId, 100, "sourceEventId");
            requireUuid(idempotencyKey, "idempotencyKey");
        }
    }

    public record ManualRecoveryRegistration(
            ManualRecoveryRegistrationStatus status,
            String handoffId
    ) {
        public ManualRecoveryRegistration {
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
            if (status == ManualRecoveryRegistrationStatus.NOT_REQUIRED) {
                if (handoffId != null) {
                    throw new IllegalArgumentException("NOT_REQUIRED must not expose handoffId");
                }
            } else {
                requirePublicId(handoffId, "handoffId");
            }
        }
    }

    public record ClaimManualRecoveryHandoffsCommand(String owner, int limit) {
        public ClaimManualRecoveryHandoffsCommand {
            requireText(owner, 64, "owner");
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
        }
    }

    public record ManualRecoveryHandoffClaim(
            String handoffId,
            ManualRecoverySourceType sourceType,
            String sourceId,
            String owner,
            long claimToken
    ) {
        public ManualRecoveryHandoffClaim {
            requirePublicId(handoffId, "handoffId");
            if (sourceType == null) {
                throw new IllegalArgumentException("sourceType must not be null");
            }
            requirePublicId(sourceId, "sourceId");
            requireText(owner, 64, "owner");
            requirePositive(claimToken, "claimToken");
        }
    }

    public record AcknowledgeManualRecoveryHandoffCommand(
            String handoffId,
            String owner,
            long claimToken,
            String adminCaseId
    ) {
        public AcknowledgeManualRecoveryHandoffCommand {
            requirePublicId(handoffId, "handoffId");
            requireText(owner, 64, "owner");
            requirePositive(claimToken, "claimToken");
            requireText(adminCaseId, 100, "adminCaseId");
        }
    }

    public record InspectManualRecoveryQuery(String handoffId) {
        public InspectManualRecoveryQuery {
            requirePublicId(handoffId, "handoffId");
        }
    }

    public record ReconcileManualRecoveryCommand(
            String handoffId,
            long expectedHandoffVersion,
            long expectedPaymentVersion,
            long expectedRecoveryVersion
    ) {
        public ReconcileManualRecoveryCommand {
            requirePublicId(handoffId, "handoffId");
            requireVersion(expectedHandoffVersion, "expectedHandoffVersion");
            requireVersion(expectedPaymentVersion, "expectedPaymentVersion");
            requireVersion(expectedRecoveryVersion, "expectedRecoveryVersion");
        }
    }

    /** Automatic refund-result lookup. It never authorizes a new provider cancellation. */
    public record ReconcileRefundResultQuery(
            String paymentId,
            String sourceEventId,
            long requestedAmountMinor,
            String currency
    ) {
        public ReconcileRefundResultQuery {
            requirePublicId(paymentId, "paymentId");
            requireText(sourceEventId, 100, "sourceEventId");
            requirePositive(requestedAmountMinor, "requestedAmountMinor");
            requireCurrency(currency);
        }
    }

    public record PreviewManualRecoveryRefundQuery(String handoffId) {
        public PreviewManualRecoveryRefundQuery {
            requirePublicId(handoffId, "handoffId");
        }
    }

    public record RequestManualRecoveryRefundCommand(
            String handoffId,
            long expectedHandoffVersion,
            long expectedPaymentVersion,
            long expectedRefundVersion,
            String operationId
    ) {
        public RequestManualRecoveryRefundCommand {
            requirePublicId(handoffId, "handoffId");
            requireVersion(expectedHandoffVersion, "expectedHandoffVersion");
            requireVersion(expectedPaymentVersion, "expectedPaymentVersion");
            requireVersion(expectedRefundVersion, "expectedRefundVersion");
            requireUuid(operationId, "operationId");
        }
    }

    public record ManualRecoveryInspection(
            String handoffId,
            long handoffVersion,
            long paymentVersion,
            long recoveryVersion,
            ManualRecoveryKind kind,
            long originalAmountMinor,
            long cumulativeRefundedAmountMinor,
            long remainingRefundableAmountMinor,
            String currency,
            ManualRecoveryResultStatus resultStatus,
            Set<ManualRecoveryAction> allowedActions,
            String maskedProviderReference
    ) {
        public ManualRecoveryInspection {
            requirePublicId(handoffId, "handoffId");
            requireVersion(handoffVersion, "handoffVersion");
            requireVersion(paymentVersion, "paymentVersion");
            requireVersion(recoveryVersion, "recoveryVersion");
            if (kind == null || resultStatus == null || allowedActions == null) {
                throw new IllegalArgumentException("kind, resultStatus, and allowedActions are required");
            }
            requirePositive(originalAmountMinor, "originalAmountMinor");
            requireNonNegative(cumulativeRefundedAmountMinor, "cumulativeRefundedAmountMinor");
            requireNonNegative(remainingRefundableAmountMinor, "remainingRefundableAmountMinor");
            requireCurrency(currency);
            allowedActions = Set.copyOf(allowedActions);
            if (maskedProviderReference != null
                    && !MASKED_PROVIDER_REFERENCE.matcher(maskedProviderReference).matches()) {
                throw new IllegalArgumentException("maskedProviderReference is invalid");
            }
        }
    }

    public record ManualRecoveryRefundPreview(
            String handoffId,
            long handoffVersion,
            long paymentVersion,
            long refundVersion,
            long originalAmountMinor,
            long requestedAmountMinor,
            long cumulativeRefundedAmountMinor,
            long remainingRefundableAmountMinor,
            String currency,
            boolean retryable
    ) {
        public ManualRecoveryRefundPreview {
            requirePublicId(handoffId, "handoffId");
            requireVersion(handoffVersion, "handoffVersion");
            requireVersion(paymentVersion, "paymentVersion");
            requireVersion(refundVersion, "refundVersion");
            requirePositive(originalAmountMinor, "originalAmountMinor");
            requirePositive(requestedAmountMinor, "requestedAmountMinor");
            requireNonNegative(cumulativeRefundedAmountMinor, "cumulativeRefundedAmountMinor");
            requireNonNegative(remainingRefundableAmountMinor, "remainingRefundableAmountMinor");
            requireCurrency(currency);
        }
    }

    private static void requirePublicId(String value, String field) {
        if (value == null || !value.matches("^[1-9][0-9]{0,18}$")) {
            throw new IllegalArgumentException(field + " must be a positive numeric string");
        }
    }

    private static void requireUuid(String value, String field) {
        if (value == null || !UUID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a normalized UUID");
        }
    }

    private static void requireCurrency(String value) {
        if (value == null || !value.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code");
        }
    }

    private static void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain valid text");
        }
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    private static void requireVersion(long value, String field) {
        requireNonNegative(value, field);
    }
}
