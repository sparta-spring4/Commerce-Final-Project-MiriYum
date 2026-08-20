package com.miriyum.domain.platformoperator.paymentrecovery.service;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.AcknowledgeManualRecoveryHandoffCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ClaimManualRecoveryHandoffsCommand;
import com.miriyum.domain.payment.service.PaymentService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PaymentRecoveryIntakeJob {
    private final PaymentService payments;
    private final PaymentRecoveryIntakeService intake;
    private final String scheduledOwner = "payment-recovery-intake-" + UUID.randomUUID();

    public PaymentRecoveryIntakeJob(PaymentService payments, PaymentRecoveryIntakeService intake) {
        this.payments = payments;
        this.intake = intake;
    }

    @Scheduled(
            fixedDelayString = "${miriyum.platform-operator.payment-recovery.intake-delay-ms:5000}",
            initialDelayString = "${miriyum.platform-operator.payment-recovery.intake-initial-delay-ms:30000}")
    public void runScheduled() {
        runOnce(scheduledOwner, 50);
    }

    public void runOnce(String owner, int limit) {
        var claims = payments.claimManualRecoveryHandoffs(
                new ClaimManualRecoveryHandoffsCommand(owner, limit));
        for (var claim : claims) {
            var recovery = intake.createOrReplay(claim);
            payments.acknowledgeManualRecoveryHandoff(
                    new AcknowledgeManualRecoveryHandoffCommand(
                            claim.handoffId(), claim.owner(), claim.claimToken(),
                            recovery.getPublicId()));
        }
    }
}
