package com.miriyum.domain.platformoperator.paymentrecovery.service;

import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.global.idempotency.RequestFingerprint;
import org.springframework.stereotype.Component;

@Component
public class PaymentRecoveryRequestFingerprint {
    public String proposal(
            String casePublicId, long proposalVersion, RecoveryAction action,
            long requestedAmountMinor, long expectedCaseVersion,
            long expectedHandoffVersion, long expectedPaymentVersion,
            long expectedRecoveryVersion) {
        return RequestFingerprint.of(String.join("\n",
                "payment-recovery-proposal-v1",
                "case=" + casePublicId,
                "proposalVersion=" + proposalVersion,
                "action=" + action,
                "amount=" + requestedAmountMinor,
                "caseVersion=" + expectedCaseVersion,
                "handoffVersion=" + expectedHandoffVersion,
                "paymentVersion=" + expectedPaymentVersion,
                "recoveryVersion=" + expectedRecoveryVersion));
    }
}
