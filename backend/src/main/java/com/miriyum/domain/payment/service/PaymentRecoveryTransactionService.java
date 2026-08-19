package com.miriyum.domain.payment.service;

import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.DISPOSITION_FAILED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.DISPOSITION_RESULT_UNKNOWN;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.REFUND_FAILED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.REFUND_RESULT_UNKNOWN;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistrationStatus.ALREADY_REGISTERED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistrationStatus.NOT_REQUIRED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistrationStatus.REGISTERED;

import com.miriyum.domain.payment.dto.PaymentContracts.DispositionStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.AcknowledgeManualRecoveryHandoffCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ClaimManualRecoveryHandoffsCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryHandoffClaim;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistration;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.RegisterManualRecoveryHandoffCommand;
import com.miriyum.domain.payment.entity.Payment;
import com.miriyum.domain.payment.entity.PaymentRecoveryHandoff;
import com.miriyum.domain.payment.entity.PaymentRefund;
import com.miriyum.domain.payment.entity.ReservationDepositDisposition;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.domain.payment.repository.PaymentRecoveryHandoffRepository;
import com.miriyum.domain.payment.repository.PaymentRefundRepository;
import com.miriyum.domain.payment.repository.PaymentRepository;
import com.miriyum.domain.payment.repository.ReservationDepositDispositionRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Payment 수동 복구의 짧은 잠금·상태 transaction 경계다. */
@Service
public class PaymentRecoveryTransactionService {

    private static final Duration INTAKE_LEASE = Duration.ofSeconds(30);

    private final PaymentRecoveryHandoffRepository handoffs;
    private final PaymentRepository payments;
    private final PaymentRefundRepository refunds;
    private final ReservationDepositDispositionRepository dispositions;
    private final Clock clock;

    public PaymentRecoveryTransactionService(
            PaymentRecoveryHandoffRepository handoffs,
            PaymentRepository payments,
            PaymentRefundRepository refunds,
            ReservationDepositDispositionRepository dispositions,
            Clock clock
    ) {
        this.handoffs = handoffs;
        this.payments = payments;
        this.refunds = refunds;
        this.dispositions = dispositions;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ManualRecoveryRegistration register(RegisterManualRecoveryHandoffCommand command) {
        PaymentRecoveryHandoff replay = handoffs.findBySourceTypeAndSourceIdForUpdate(
                command.sourceType(), command.sourceId()).orElse(null);
        if (replay != null) {
            if (!replay.getPaymentId().equals(command.paymentId())
                    || !replay.getSourceEventId().equals(command.sourceEventId())
                    || !replay.getRegistrationIdempotencyKey().equals(command.idempotencyKey())) {
                throw new ServiceException(PaymentErrorCode.PAYMENT_RECOVERY_STALE);
            }
            return new ManualRecoveryRegistration(
                    ALREADY_REGISTERED, Long.toString(replay.getId()));
        }

        Payment payment = payments.findByPaymentIdForUpdate(command.paymentId())
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        ManualRecoveryKind kind = deriveKind(command, payment);
        if (kind == null) {
            return new ManualRecoveryRegistration(NOT_REQUIRED, null);
        }
        PaymentRecoveryHandoff saved = handoffs.saveAndFlush(PaymentRecoveryHandoff.register(
                command.sourceType(), command.sourceId(), payment.getId(),
                payment.getPaymentId(), command.sourceEventId(), kind,
                command.idempotencyKey(), clock.instant()));
        return new ManualRecoveryRegistration(REGISTERED, Long.toString(saved.getId()));
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public List<ManualRecoveryHandoffClaim> claim(
            ClaimManualRecoveryHandoffsCommand command
    ) {
        Instant now = clock.instant();
        return handoffs.findClaimableForUpdate(now, PageRequest.of(0, command.limit()))
                .stream()
                .map(handoff -> {
                    handoff.claim(command.owner(), now, now.plus(INTAKE_LEASE));
                    handoffs.save(handoff);
                    return new ManualRecoveryHandoffClaim(
                            Long.toString(handoff.getId()), handoff.getSourceType(),
                            handoff.getSourceId(), command.owner(), handoff.getClaimToken());
                })
                .toList();
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public void acknowledge(AcknowledgeManualRecoveryHandoffCommand command) {
        PaymentRecoveryHandoff handoff = handoffs.findByIdForUpdate(
                        Long.parseLong(command.handoffId()))
                .orElseThrow(() -> new ServiceException(
                        PaymentErrorCode.PAYMENT_RECOVERY_NOT_FOUND));
        try {
            handoff.acknowledge(command.owner(), command.claimToken(),
                    command.adminCaseId(), clock.instant());
            handoffs.saveAndFlush(handoff);
        } catch (IllegalStateException stale) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_RECOVERY_STALE);
        }
    }

    private ManualRecoveryKind deriveKind(
            RegisterManualRecoveryHandoffCommand command,
            Payment payment
    ) {
        return switch (command.sourceType()) {
            case RESERVATION_DEPOSIT_REFUND -> refundKind(payment, command.sourceEventId());
            case RESERVATION_DEPOSIT_DISPOSITION ->
                    dispositionKind(payment, command.sourceEventId());
        };
    }

    private ManualRecoveryKind refundKind(Payment payment, String sourceEventId) {
        PaymentRefund refund = refunds.findByPayment_IdAndSourceEventIdForUpdate(
                        payment.getId(), sourceEventId)
                .orElseThrow(() -> new ServiceException(
                        PaymentErrorCode.PAYMENT_RECOVERY_NOT_REQUIRED));
        if (refund.getStatus() == RefundStatus.RECONCILIATION_REQUIRED) {
            return REFUND_RESULT_UNKNOWN;
        }
        if (refund.getStatus() == RefundStatus.FAILED) {
            return REFUND_FAILED;
        }
        return null;
    }

    private ManualRecoveryKind dispositionKind(Payment payment, String sourceEventId) {
        ReservationDepositDisposition disposition = dispositions
                .findByPayment_IdAndSourceEventIdForUpdate(payment.getId(), sourceEventId)
                .orElseThrow(() -> new ServiceException(
                        PaymentErrorCode.PAYMENT_RECOVERY_NOT_REQUIRED));
        if (disposition.getStatus() == DispositionStatus.RECONCILIATION_REQUIRED) {
            return DISPOSITION_RESULT_UNKNOWN;
        }
        if (disposition.getStatus() == DispositionStatus.FAILED) {
            return DISPOSITION_FAILED;
        }
        return null;
    }
}
