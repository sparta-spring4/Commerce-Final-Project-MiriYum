package com.miriyum.domain.platformoperator.paymentrecovery.service;

import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ReconcileManualRecoveryCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.InspectManualRecoveryQuery;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryInspection;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryResultStatus;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.RequestManualRecoveryRefundCommand;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PaymentRecoveryExecutionJob {
    private final PaymentRecoveryExecutionTransaction transactions;
    private final PaymentService payments;
    private final String owner;

    public PaymentRecoveryExecutionJob(
            PaymentRecoveryExecutionTransaction transactions, PaymentService payments,
            @Value("${miriyum.platform-operator.payment-recovery-worker-id:${random.uuid}}") String owner) {
        this.transactions = transactions;
        this.payments = payments;
        this.owner = owner == null || owner.isBlank() ? UUID.randomUUID().toString() : owner;
    }

    @Scheduled(initialDelayString = "${miriyum.platform-operator.payment-recovery-initial-delay-ms:30000}",
            fixedDelayString = "${miriyum.platform-operator.payment-recovery-delay-ms:5000}")
    public void runOnce() {
        transactions.claim(owner).ifPresent(this::execute);
    }

    private void execute(PaymentRecoveryExecutionTransaction.Claim claim) {
        if (claim.operation() == RecoveryAction.RETRY_REFUND) {
            RefundResult result;
            try {
                result = payments.requestManualRecoveryRefund(new RequestManualRecoveryRefundCommand(
                        claim.handoffId(), claim.expectedHandoffVersion(), claim.expectedPaymentVersion(),
                        claim.expectedRecoveryVersion(), claim.operationId()));
            } catch (RuntimeException failure) {
                transactions.recordFailure(claim);
                return;
            }
            if (result.status() == RefundStatus.FAILED) {
                transactions.recordProviderFailure(claim);
            } else {
                transactions.scheduleLookup(claim,
                        result.status() == RefundStatus.RECONCILIATION_REQUIRED
                                ? "RESULT_UNKNOWN" : "VERIFY_REQUIRED");
            }
            return;
        }

        ManualRecoveryInspection inspection;
        try {
            var current = payments.inspectManualRecovery(
                        new InspectManualRecoveryQuery(claim.handoffId()));
            if (current.resultStatus() != ManualRecoveryResultStatus.UNKNOWN) {
                inspection = current;
            } else {
                inspection = payments.reconcileManualRecovery(new ReconcileManualRecoveryCommand(
                        claim.handoffId(), current.handoffVersion(), current.paymentVersion(),
                        current.recoveryVersion()));
            }
        } catch (RuntimeException failure) {
            transactions.recordFailure(claim);
            return;
        }
        transactions.recordInspection(claim, inspection);
    }
}
