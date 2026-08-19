package com.miriyum.domain.platformoperator.paymentrecovery.service;

import static java.util.Map.entry;

import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import java.util.Map;

public final class PaymentRecoveryAuditSnapshots {
    private PaymentRecoveryAuditSnapshots() {
    }

    public static Map<String, Object> caseSnapshot(PaymentRecoveryCase value) {
        return Map.ofEntries(
                entry("caseId", value.getPublicId()),
                entry("caseVersion", value.getCaseVersion()),
                entry("status", value.getStatus().name()),
                entry("recoveryKind", value.getRecoveryKind().name()),
                entry("resultStatus", value.getResultStatus().name()),
                entry("originalAmountMinor", value.getOriginalAmountMinor()),
                entry("cumulativeRefundedAmountMinor", value.getCumulativeRefundedAmountMinor()),
                entry("remainingRefundableAmountMinor", value.getRemainingRefundableAmountMinor()),
                entry("currency", value.getCurrency()),
                entry("maskedProviderReference",
                        value.getMaskedProviderReference() == null ? "UNAVAILABLE"
                                : value.getMaskedProviderReference()),
                entry("allowedActions", value.getAllowedActions().stream().map(Enum::name).sorted().toList()),
                entry("handoffVersion", value.getHandoffVersion()),
                entry("paymentVersion", value.getPaymentVersion()),
                entry("recoveryVersion", value.getRecoveryVersion()));
    }
}
