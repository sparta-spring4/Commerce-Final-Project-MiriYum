package com.miriyum.domain.platformoperator.paymentrecovery.service;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.InspectManualRecoveryQuery;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryHandoffClaim;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryInspection;
import com.miriyum.domain.payment.service.PaymentService;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCase;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryAction;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.RecoveryKind;
import com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryEnums.ResultStatus;
import com.miriyum.domain.platformoperator.paymentrecovery.repository.PaymentRecoveryCaseRepository;
import java.time.Clock;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PaymentRecoveryIntakeService {
    private final PaymentRecoveryCaseRepository cases;
    private final PaymentService payments;
    private final Clock clock;

    public PaymentRecoveryIntakeService(
            PaymentRecoveryCaseRepository cases,
            PaymentService payments,
            Clock clock
    ) {
        this.cases = cases;
        this.payments = payments;
        this.clock = clock;
    }

    @Transactional
    public PaymentRecoveryCase createOrReplay(ManualRecoveryHandoffClaim claim) {
        return cases.findByHandoffId(claim.handoffId()).orElseGet(() -> create(claim));
    }

    private PaymentRecoveryCase create(ManualRecoveryHandoffClaim claim) {
        ManualRecoveryInspection inspection = payments.inspectManualRecovery(
                new InspectManualRecoveryQuery(claim.handoffId()));
        if (!inspection.handoffId().equals(claim.handoffId())) {
            throw new IllegalStateException("Payment recovery handoff inspection mismatch");
        }
        PaymentRecoveryCase recovery = PaymentRecoveryCase.open(
                inspection.handoffId(),
                RecoveryKind.valueOf(inspection.kind().name()),
                ResultStatus.valueOf(inspection.resultStatus().name()),
                inspection.originalAmountMinor(),
                inspection.cumulativeRefundedAmountMinor(),
                inspection.remainingRefundableAmountMinor(),
                inspection.currency(),
                inspection.allowedActions().stream()
                        .map(action -> RecoveryAction.valueOf(action.name()))
                        .collect(Collectors.toUnmodifiableSet()),
                inspection.maskedProviderReference(),
                inspection.handoffVersion(),
                inspection.paymentVersion(),
                inspection.recoveryVersion(),
                clock.instant());
        return cases.saveAndFlush(recovery);
    }
}
