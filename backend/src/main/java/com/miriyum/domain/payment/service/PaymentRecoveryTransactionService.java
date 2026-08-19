package com.miriyum.domain.payment.service;

import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.DISPOSITION_FAILED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.DISPOSITION_RESULT_UNKNOWN;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.REFUND_FAILED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind.REFUND_RESULT_UNKNOWN;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistrationStatus.ALREADY_REGISTERED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistrationStatus.NOT_REQUIRED;
import static com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistrationStatus.REGISTERED;

import com.miriyum.domain.payment.dto.PaymentContracts.DispositionStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundStatus;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.AcknowledgeManualRecoveryHandoffCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ClaimManualRecoveryHandoffsCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.InspectManualRecoveryQuery;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryAction;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryHandoffClaim;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryInspection;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryKind;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistration;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryResultStatus;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRefundPreview;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.PreviewManualRecoveryRefundQuery;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.RegisterManualRecoveryHandoffCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ReconcileManualRecoveryCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.RequestManualRecoveryRefundCommand;
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
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Payment 수동 복구의 짧은 잠금·상태 transaction 경계다. */
@Service
public class PaymentRecoveryTransactionService {

    enum ReconciliationTarget { REFUND, DISPOSITION }

    record ManualReconciliationClaim(
            ReconciliationTarget target,
            String handoffId,
            PaymentTransactionService.RefundClaim refundClaim,
            String paymentId,
            String sourceEventId,
            long paymentAmountMinor
    ) {
        static ManualReconciliationClaim refund(
                String handoffId,
                PaymentTransactionService.RefundClaim refundClaim,
                long paymentAmountMinor
        ) {
            return new ManualReconciliationClaim(
                    ReconciliationTarget.REFUND, handoffId, refundClaim, null, null,
                    paymentAmountMinor);
        }

        static ManualReconciliationClaim disposition(
                String handoffId,
                String paymentId,
                String sourceEventId
        ) {
            return new ManualReconciliationClaim(
                    ReconciliationTarget.DISPOSITION, handoffId, null,
                    paymentId, sourceEventId, 0L);
        }
    }

    record ManualRefundExecutionClaim(
            RequestRefundCommand command,
            RefundResult replayResult
    ) {
        static ManualRefundExecutionClaim execute(RequestRefundCommand command) {
            return new ManualRefundExecutionClaim(command, null);
        }

        static ManualRefundExecutionClaim replay(RefundResult result) {
            return new ManualRefundExecutionClaim(null, result);
        }
    }

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
        PaymentRecoveryHandoff serializedReplay = handoffs
                .findBySourceTypeAndSourceIdForUpdate(
                        command.sourceType(), command.sourceId())
                .orElse(null);
        if (serializedReplay != null) {
            if (!serializedReplay.getPaymentId().equals(command.paymentId())
                    || !serializedReplay.getSourceEventId().equals(command.sourceEventId())
                    || !serializedReplay.getRegistrationIdempotencyKey()
                    .equals(command.idempotencyKey())) {
                throw new ServiceException(PaymentErrorCode.PAYMENT_RECOVERY_STALE);
            }
            return new ManualRecoveryRegistration(
                    ALREADY_REGISTERED, Long.toString(serializedReplay.getId()));
        }
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

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ManualRecoveryInspection inspect(InspectManualRecoveryQuery query) {
        PaymentRecoveryHandoff handoff = handoffs.findByIdForUpdate(
                        Long.parseLong(query.handoffId()))
                .orElseThrow(() -> new ServiceException(
                        PaymentErrorCode.PAYMENT_RECOVERY_NOT_FOUND));
        Payment payment = payments.findByPaymentIdForUpdate(handoff.getPaymentId())
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        return switch (handoff.getRecoveryKind()) {
            case REFUND_RESULT_UNKNOWN, REFUND_FAILED -> inspectRefund(handoff, payment);
            case DISPOSITION_RESULT_UNKNOWN, DISPOSITION_FAILED ->
                    inspectDisposition(handoff, payment);
        };
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ManualReconciliationClaim claimReconciliation(
            ReconcileManualRecoveryCommand command
    ) {
        PaymentRecoveryHandoff handoff = handoffs.findByIdForUpdate(
                        Long.parseLong(command.handoffId()))
                .orElseThrow(() -> new ServiceException(
                        PaymentErrorCode.PAYMENT_RECOVERY_NOT_FOUND));
        Payment payment = payments.findByPaymentIdForUpdate(handoff.getPaymentId())
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (handoff.getRowVersion() != command.expectedHandoffVersion()
                || payment.getVersion() != command.expectedPaymentVersion()) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_RECOVERY_STALE);
        }
        return switch (handoff.getRecoveryKind()) {
            case REFUND_RESULT_UNKNOWN -> claimRefundReconciliation(
                    command, handoff, payment);
            case DISPOSITION_RESULT_UNKNOWN -> claimDispositionReconciliation(
                    command, handoff, payment);
            case REFUND_FAILED, DISPOSITION_FAILED -> throw new ServiceException(
                    PaymentErrorCode.PAYMENT_RECOVERY_NOT_REQUIRED);
        };
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ManualRecoveryRefundPreview preview(PreviewManualRecoveryRefundQuery query) {
        RecoveryRefundSnapshot snapshot = recoveryRefund(query.handoffId());
        return new ManualRecoveryRefundPreview(
                query.handoffId(), snapshot.handoff().getRowVersion(),
                snapshot.payment().getVersion(), snapshot.refund().getVersion(),
                snapshot.payment().getAmountMinor(), snapshot.refund().getAmountMinor(),
                snapshot.payment().getRefundedAmountMinor(),
                snapshot.payment().getRefundableAmountMinor(),
                snapshot.payment().getCurrency(),
                snapshot.refund().getStatus() == RefundStatus.FAILED);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public ManualRefundExecutionClaim claimRefundExecution(
            RequestManualRecoveryRefundCommand command
    ) {
        RecoveryRefundSnapshot snapshot = recoveryRefund(command.handoffId());
        PaymentRecoveryHandoff handoff = snapshot.handoff();
        Payment payment = snapshot.payment();
        PaymentRefund refund = snapshot.refund();
        if (command.operationId().equals(handoff.getOperationId())) {
            return ManualRefundExecutionClaim.replay(toRefundResult(payment, refund));
        }
        if (handoff.getRowVersion() != command.expectedHandoffVersion()
                || payment.getVersion() != command.expectedPaymentVersion()
                || refund.getVersion() != command.expectedRefundVersion()) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_RECOVERY_STALE);
        }
        if (handoff.getRecoveryKind() != REFUND_FAILED
                || refund.getStatus() != RefundStatus.FAILED) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_RECOVERY_NOT_REQUIRED);
        }
        try {
            handoff.beginOperation(command.operationId(), clock.instant());
            handoffs.saveAndFlush(handoff);
        } catch (IllegalStateException stale) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_RECOVERY_STALE);
        }
        return ManualRefundExecutionClaim.execute(new RequestRefundCommand(
                payment.getPaymentId(), refund.getSourceEventId(), refund.getAmountMinor(),
                refund.getReasonCode(), refund.getPolicyVersion(), refund.getIdempotencyKey()));
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public void finishRefundExecution(
            String handoffId,
            String operationId,
            RefundResult result
    ) {
        PaymentRecoveryHandoff handoff = handoffs.findByIdForUpdate(Long.parseLong(handoffId))
                .orElseThrow(() -> new ServiceException(
                        PaymentErrorCode.PAYMENT_RECOVERY_NOT_FOUND));
        PaymentRecoveryHandoff.OperationStatus status = switch (result.status()) {
            case COMPLETED -> PaymentRecoveryHandoff.OperationStatus.SUCCEEDED;
            case FAILED -> PaymentRecoveryHandoff.OperationStatus.FAILED;
            default -> PaymentRecoveryHandoff.OperationStatus.UNKNOWN;
        };
        finishOperation(handoff, operationId, status);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5)
    public void markRefundExecutionUnknown(String handoffId, String operationId) {
        PaymentRecoveryHandoff handoff = handoffs.findByIdForUpdate(Long.parseLong(handoffId))
                .orElseThrow(() -> new ServiceException(
                        PaymentErrorCode.PAYMENT_RECOVERY_NOT_FOUND));
        finishOperation(handoff, operationId, PaymentRecoveryHandoff.OperationStatus.UNKNOWN);
    }

    private void finishOperation(
            PaymentRecoveryHandoff handoff,
            String operationId,
            PaymentRecoveryHandoff.OperationStatus status
    ) {
        try {
            handoff.finishOperation(operationId, status, clock.instant());
            handoffs.saveAndFlush(handoff);
        } catch (IllegalStateException stale) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_RECOVERY_STALE);
        }
    }

    private RecoveryRefundSnapshot recoveryRefund(String handoffId) {
        PaymentRecoveryHandoff handoff = handoffs.findByIdForUpdate(Long.parseLong(handoffId))
                .orElseThrow(() -> new ServiceException(
                        PaymentErrorCode.PAYMENT_RECOVERY_NOT_FOUND));
        if (handoff.getRecoveryKind() != REFUND_FAILED
                && handoff.getRecoveryKind() != REFUND_RESULT_UNKNOWN) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_RECOVERY_NOT_SUPPORTED);
        }
        Payment payment = payments.findByPaymentIdForUpdate(handoff.getPaymentId())
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        PaymentRefund refund = refunds.findByPayment_IdAndSourceEventIdForUpdate(
                        payment.getId(), handoff.getSourceEventId())
                .orElseThrow(() -> new ServiceException(
                        PaymentErrorCode.PAYMENT_RECOVERY_NOT_FOUND));
        return new RecoveryRefundSnapshot(handoff, payment, refund);
    }

    private static RefundResult toRefundResult(Payment payment, PaymentRefund refund) {
        long completed = refund.getStatus() == RefundStatus.COMPLETED
                ? refund.getAmountMinor() : 0L;
        return new RefundResult(
                refund.getRefundId(), payment.getPaymentId(), refund.getAmountMinor(),
                completed, payment.getRefundedAmountMinor(),
                payment.getRefundableAmountMinor(), refund.getCurrency(), refund.getStatus(),
                refund.getRequestedAt(), refund.getCompletedAt());
    }

    private record RecoveryRefundSnapshot(
            PaymentRecoveryHandoff handoff,
            Payment payment,
            PaymentRefund refund
    ) { }

    private ManualReconciliationClaim claimRefundReconciliation(
            ReconcileManualRecoveryCommand command,
            PaymentRecoveryHandoff handoff,
            Payment payment
    ) {
        PaymentRefund refund = refunds.findByPayment_IdAndSourceEventIdForUpdate(
                        payment.getId(), handoff.getSourceEventId())
                .orElseThrow(() -> new ServiceException(
                        PaymentErrorCode.PAYMENT_RECOVERY_NOT_FOUND));
        if (refund.getVersion() != command.expectedRecoveryVersion()) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_RECOVERY_STALE);
        }
        if (refund.getStatus() != RefundStatus.RECONCILIATION_REQUIRED) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_RECOVERY_NOT_REQUIRED);
        }
        return ManualReconciliationClaim.refund(command.handoffId(),
                PaymentTransactionService.RefundClaim.requiresCall(
                        refund.getRefundId(), payment.getPaymentId(),
                        payment.getPortOnePaymentId(), refund.getAmountMinor(),
                        refund.getCurrency(), refund.getReasonCode()),
                payment.getAmountMinor());
    }

    private ManualReconciliationClaim claimDispositionReconciliation(
            ReconcileManualRecoveryCommand command,
            PaymentRecoveryHandoff handoff,
            Payment payment
    ) {
        ReservationDepositDisposition disposition = dispositions
                .findByPayment_IdAndSourceEventIdForUpdate(
                        payment.getId(), handoff.getSourceEventId())
                .orElseThrow(() -> new ServiceException(
                        PaymentErrorCode.PAYMENT_RECOVERY_NOT_FOUND));
        if (disposition.getVersion() != command.expectedRecoveryVersion()) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_RECOVERY_STALE);
        }
        if (disposition.getStatus() != DispositionStatus.RECONCILIATION_REQUIRED) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_RECOVERY_NOT_REQUIRED);
        }
        return ManualReconciliationClaim.disposition(
                command.handoffId(), payment.getPaymentId(), handoff.getSourceEventId());
    }

    private ManualRecoveryInspection inspectRefund(
            PaymentRecoveryHandoff handoff,
            Payment payment
    ) {
        PaymentRefund refund = refunds.findByPayment_IdAndSourceEventIdForUpdate(
                        payment.getId(), handoff.getSourceEventId())
                .orElseThrow(() -> new ServiceException(
                        PaymentErrorCode.PAYMENT_RECOVERY_NOT_FOUND));
        ManualRecoveryResultStatus status = switch (refund.getStatus()) {
            case RECONCILIATION_REQUIRED -> ManualRecoveryResultStatus.UNKNOWN;
            case FAILED -> ManualRecoveryResultStatus.FAILED;
            case COMPLETED -> ManualRecoveryResultStatus.SUCCEEDED;
            default -> ManualRecoveryResultStatus.UNKNOWN;
        };
        return inspection(handoff, payment, refund.getVersion(), status);
    }

    private ManualRecoveryInspection inspectDisposition(
            PaymentRecoveryHandoff handoff,
            Payment payment
    ) {
        ReservationDepositDisposition disposition = dispositions
                .findByPayment_IdAndSourceEventIdForUpdate(
                        payment.getId(), handoff.getSourceEventId())
                .orElseThrow(() -> new ServiceException(
                        PaymentErrorCode.PAYMENT_RECOVERY_NOT_FOUND));
        ManualRecoveryResultStatus status = switch (disposition.getStatus()) {
            case RECONCILIATION_REQUIRED -> ManualRecoveryResultStatus.UNKNOWN;
            case FAILED -> ManualRecoveryResultStatus.FAILED;
            case COMPLETED -> ManualRecoveryResultStatus.SUCCEEDED;
            default -> ManualRecoveryResultStatus.UNKNOWN;
        };
        return inspection(handoff, payment, disposition.getVersion(), status);
    }

    private ManualRecoveryInspection inspection(
            PaymentRecoveryHandoff handoff,
            Payment payment,
            long recoveryVersion,
            ManualRecoveryResultStatus status
    ) {
        Set<ManualRecoveryAction> actions = switch (status) {
            case UNKNOWN -> Set.of(ManualRecoveryAction.REQUERY_PROVIDER_RESULT);
            case FAILED -> handoff.getRecoveryKind() == REFUND_FAILED
                    ? Set.of(ManualRecoveryAction.RETRY_REFUND)
                    : Set.of();
            case SUCCEEDED -> Set.of();
        };
        return new ManualRecoveryInspection(
                Long.toString(handoff.getId()), handoff.getRowVersion(),
                payment.getVersion(), recoveryVersion, handoff.getRecoveryKind(),
                payment.getAmountMinor(), payment.getRefundedAmountMinor(),
                payment.getRefundableAmountMinor(), payment.getCurrency(), status,
                actions, maskProviderReference(payment.getPortOnePaymentId()));
    }

    private static String maskProviderReference(String providerReference) {
        int suffixLength = Math.min(4, providerReference.length());
        return "port********" + providerReference.substring(
                providerReference.length() - suffixLength);
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
