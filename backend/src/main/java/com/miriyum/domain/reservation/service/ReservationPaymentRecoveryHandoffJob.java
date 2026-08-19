package com.miriyum.domain.reservation.service;

import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistration;
import com.miriyum.domain.payment.service.PaymentService;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Reservation 복구 outbox를 Payment 공개 계약으로 전달한다. */
@Component
public class ReservationPaymentRecoveryHandoffJob {

    static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final ReservationPaymentRecoveryOutboxService outbox;
    private final PaymentService paymentService;
    private final String owner;
    private final int batchSize;

    @Autowired
    public ReservationPaymentRecoveryHandoffJob(
            ReservationPaymentRecoveryOutboxService outbox,
            PaymentService paymentService,
            @Qualifier("reservationPaymentRecoveryHandoffBatchSize") Integer batchSize
    ) {
        this(outbox, paymentService,
                "payment-recovery-handoff-" + UUID.randomUUID(),
                requireBatchSize(batchSize));
    }

    ReservationPaymentRecoveryHandoffJob(
            ReservationPaymentRecoveryOutboxService outbox,
            PaymentService paymentService,
            String owner,
            int batchSize
    ) {
        this.outbox = outbox;
        this.paymentService = paymentService;
        if (owner == null || owner.isBlank() || owner.length() > 64) {
            throw new IllegalArgumentException("owner must be 1 to 64 characters");
        }
        this.owner = owner;
        this.batchSize = requireBatchSize(batchSize);
    }

    public int runScheduled() {
        return runOnce(owner, batchSize);
    }

    public int runOnce(String owner, int limit) {
        int delivered = 0;
        for (ReservationPaymentRecoveryOutboxService.Claim claim
                : outbox.claimDue(owner, limit)) {
            try {
                ManualRecoveryRegistration ignored =
                        paymentService.registerManualRecoveryHandoff(claim.toCommand());
                if (outbox.recordDelivered(claim)) {
                    delivered++;
                }
            } catch (RuntimeException failure) {
                outbox.recordRetry(claim, RETRY_DELAY);
            }
        }
        return delivered;
    }

    private static int requireBatchSize(Integer value) {
        if (value == null || value < 1 || value > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }
        return value;
    }
}
