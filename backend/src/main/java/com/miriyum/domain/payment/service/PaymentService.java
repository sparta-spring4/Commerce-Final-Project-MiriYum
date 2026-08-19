package com.miriyum.domain.payment.service;

import com.miriyum.domain.payment.dto.PaymentContracts.ApplyReservationDepositDispositionCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.ConfirmPaymentCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.DispositionResult;
import com.miriyum.domain.payment.dto.PaymentContracts.GetReservationDepositDispositionQuery;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistoryQuery;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentHistorySlice;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentPreparation;
import com.miriyum.domain.payment.dto.PaymentContracts.PaymentResult;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.PrepareWaitingReservationDepositCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.RefundResult;
import com.miriyum.domain.payment.dto.PaymentContracts.RequestRefundCommand;
import com.miriyum.domain.payment.dto.PaymentContracts.VerifiedWaitingReservationDeposit;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.AcknowledgeManualRecoveryHandoffCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ClaimManualRecoveryHandoffsCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.InspectManualRecoveryQuery;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryHandoffClaim;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryInspection;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRefundPreview;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ManualRecoveryRegistration;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.PreviewManualRecoveryRefundQuery;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.ReconcileManualRecoveryCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.RegisterManualRecoveryHandoffCommand;
import com.miriyum.domain.payment.dto.PaymentRecoveryContracts.RequestManualRecoveryRefundCommand;
import com.miriyum.domain.payment.port.PaymentProviderClient;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderCancellation;
import com.miriyum.domain.payment.port.PaymentProviderClient.ProviderPayment;
import com.miriyum.domain.payment.exception.PaymentErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Payment의 유일한 공개 use-case Service이며 외부 호출을 DB transaction 밖에서 수행한다. */
@Service
public class PaymentService {

    private static final Set<String> RESERVATION_PREPARATION_CONSTRAINTS = Set.of(
            "uk_payments_source",
            "payments.uk_payments_source",
            "uk_payments_preparation_idempotency",
            "payments.uk_payments_preparation_idempotency"
    );

    private final PaymentTransactionService transactions;
    private final PaymentProviderClient providerClient;
    private final PaymentRecoveryTransactionService recoveryTransactions;
    private final Clock clock;

    public PaymentService(
            PaymentTransactionService transactions,
            PaymentProviderClient providerClient,
            PaymentRecoveryTransactionService recoveryTransactions,
            Clock clock
    ) {
        this.transactions = transactions;
        this.providerClient = providerClient;
        this.recoveryTransactions = recoveryTransactions;
        this.clock = clock;
    }

    public PaymentPreparation prepareReservationDeposit(PrepareReservationDepositCommand command) {
        boolean callerTransactionActive =
                TransactionSynchronizationManager.isActualTransactionActive();
        try {
            return transactions.prepare(command, now());
        } catch (DataIntegrityViolationException race) {
            if (!isRetryableReservationPreparationConflict(race)) {
                throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
            }
            if (callerTransactionActive) {
                throw new PaymentPreparationRetryableConflictException();
            }
            return transactions.replayPreparation(command);
        }
    }

    /** Prepares a Payment-owned deposit source for a waiting reservation without Reservation access. */
    public PaymentPreparation prepareWaitingReservationDeposit(
            PrepareWaitingReservationDepositCommand command
    ) {
        boolean callerTransactionActive =
                TransactionSynchronizationManager.isActualTransactionActive();
        try {
            return transactions.prepareWaitingReservationDeposit(command, now());
        } catch (DataIntegrityViolationException race) {
            if (callerTransactionActive) {
                throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
            }
            return transactions.replayWaitingReservationDeposit(command);
        }
    }

    public PaymentResult confirmPayment(ConfirmPaymentCommand command) {
        Instant now = now();
        PaymentTransactionService.ConfirmationClaim claim =
                transactions.claimConfirmation(command, now);
        return confirmClaim(claim);
    }

    PaymentResult confirmWebhook(
            String portOnePaymentId,
            String webhookMessageId,
            String webhookType,
            String webhookCancellationId
    ) {
        PaymentTransactionService.ConfirmationClaim claim =
                transactions.claimWebhookConfirmation(
                        portOnePaymentId,
                        webhookMessageId,
                        webhookType,
                        webhookCancellationId,
                        now()
                );
        return confirmClaim(claim);
    }

    private PaymentResult confirmClaim(PaymentTransactionService.ConfirmationClaim claim) {
        if (!claim.requiresProviderLookup()) {
            return claim.completedResult();
        }
        try {
            ProviderPayment providerPayment = providerClient.getPayment(claim.portOnePaymentId());
            if (!matchesProviderMapping(claim, providerPayment)) {
                transactions.markConfirmationMismatch(claim, now());
                throw new ServiceException(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);
            }
            return transactions.finalizeConfirmation(claim, providerPayment, now());
        } catch (PaymentProviderClient.ProviderUnavailableException exception) {
            return transactions.markConfirmationUnknown(claim, now());
        }
    }

    public RefundResult requestRefund(RequestRefundCommand command) {
        PaymentTransactionService.RefundClaim claim =
                transactions.claimRefund(command, now());
        if (claim.rejectionError() != null) {
            throw new ServiceException(claim.rejectionError());
        }
        if (!claim.requiresProviderCall()) {
            return claim.completedResult();
        }
        try {
            ProviderCancellation cancellation = providerClient.cancelPayment(
                    claim.portOnePaymentId(),
                    claim.refundId(),
                    claim.amountMinor(),
                    claim.currency(),
                    claim.reasonCode()
            );
            if (cancellation.amountMinor() != claim.amountMinor()
                    || !cancellation.currency().equals(claim.currency())) {
                transactions.markRefundMismatch(claim, now());
                throw new ServiceException(PaymentErrorCode.PROVIDER_MAPPING_MISMATCH);
            }
            return transactions.finalizeRefund(claim, cancellation, now());
        } catch (PaymentProviderClient.ProviderUnavailableException exception) {
            return transactions.markRefundUnknown(claim, now());
        }
    }

    /** 예약금 목표 누적 환불률을 Payment 원장과 기존 환불 machinery에 멱등 적용한다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DispositionResult applyReservationDepositDisposition(
            ApplyReservationDepositDispositionCommand command
    ) {
        PaymentTransactionService.DispositionClaim claim =
                transactions.claimDisposition(command, now());
        if (!claim.requiresRefund()) {
            return claim.completedResult();
        }
        try {
            RefundResult refundResult = requestRefund(claim.refundCommand());
            return transactions.finalizeDisposition(claim.dispositionId(), refundResult, now());
        } catch (ServiceException exception) {
            return transactions.resolveDispositionAfterRefundFailure(
                    claim.dispositionId(),
                    exception.getErrorCode() == PaymentErrorCode.REFUND_AMOUNT_EXCEEDED,
                    now());
        }
    }

    /** 새 외부 환불 없이 결과 불명인 처분만 provider 취소 조회로 대사한다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DispositionResult getReservationDepositDisposition(
            GetReservationDepositDispositionQuery query
    ) {
        PaymentTransactionService.DispositionReconciliationClaim claim =
                transactions.claimDispositionReconciliation(query, now());
        if (!claim.requiresProviderLookup()) {
            return claim.completedResult();
        }
        try {
            ProviderPayment providerPayment = providerClient.getPayment(claim.portOnePaymentId());
            if (!claim.portOnePaymentId().equals(providerPayment.portOnePaymentId())
                    || claim.paymentAmountMinor() != providerPayment.amountMinor()
                    || !claim.currency().equals(providerPayment.currency())) {
                return claim.completedResult();
            }
            String expectedReason = PaymentProviderClient.cancellationReason(
                    claim.reasonCode(), claim.refundId());
            java.util.List<ProviderCancellation> matching = providerPayment.cancellations()
                    .stream()
                    .filter(cancellation -> expectedReason.equals(cancellation.reason()))
                    .toList();
            if (matching.size() != 1
                    || matching.getFirst().amountMinor() != claim.refundAmountMinor()
                    || !claim.currency().equals(matching.getFirst().currency())) {
                return claim.completedResult();
            }
            return transactions.finalizeDispositionReconciliation(
                    claim, matching.getFirst(), now());
        } catch (PaymentProviderClient.ProviderUnavailableException exception) {
            return claim.completedResult();
        }
    }

    public PaymentResult getOwnedPayment(String paymentId, String consumerAccountId) {
        return transactions.getOwnedPayment(paymentId, parsePositiveId(consumerAccountId));
    }

    public VerifiedWaitingReservationDeposit getVerifiedWaitingReservationDeposit(
            String paymentId,
            long waitingTeamId,
            long consumerAccountId
    ) {
        return transactions.getVerifiedWaitingReservationDeposit(
                paymentId, waitingTeamId, consumerAccountId);
    }

    public VerifiedWaitingReservationDeposit getCompletableWaitingReservationDeposit(
            String paymentId,
            long waitingTeamId,
            long consumerAccountId
    ) {
        return transactions.getCompletableWaitingReservationDeposit(
                paymentId, waitingTeamId, consumerAccountId);
    }

    public PaymentHistorySlice getConsumerPaymentHistory(PaymentHistoryQuery query) {
        return transactions.getConsumerPaymentHistory(query);
    }

    public ManualRecoveryInspection inspectManualRecovery(
            InspectManualRecoveryQuery query
    ) {
        return recoveryTransactions.inspect(query);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ManualRecoveryInspection reconcileManualRecovery(
            ReconcileManualRecoveryCommand command
    ) {
        PaymentRecoveryTransactionService.ManualReconciliationClaim claim =
                recoveryTransactions.claimReconciliation(command);
        if (claim.target()
                == PaymentRecoveryTransactionService.ReconciliationTarget.DISPOSITION) {
            getReservationDepositDisposition(new GetReservationDepositDispositionQuery(
                    claim.paymentId(), claim.sourceEventId()));
            return recoveryTransactions.inspect(
                    new InspectManualRecoveryQuery(claim.handoffId()));
        }
        PaymentTransactionService.RefundClaim refundClaim = claim.refundClaim();
        try {
            ProviderPayment providerPayment = providerClient.getPayment(
                    refundClaim.portOnePaymentId());
            String expectedReason = PaymentProviderClient.cancellationReason(
                    refundClaim.reasonCode(), refundClaim.refundId());
            List<ProviderCancellation> matching = providerPayment.cancellations().stream()
                    .filter(cancellation -> expectedReason.equals(cancellation.reason()))
                    .toList();
            if (refundClaim.portOnePaymentId().equals(providerPayment.portOnePaymentId())
                    && claim.paymentAmountMinor() == providerPayment.amountMinor()
                    && refundClaim.currency().equals(providerPayment.currency())
                    && matching.size() == 1
                    && matching.getFirst().amountMinor() == refundClaim.amountMinor()
                    && refundClaim.currency().equals(matching.getFirst().currency())) {
                transactions.finalizeRefund(refundClaim, matching.getFirst(), now());
            }
        } catch (PaymentProviderClient.ProviderUnavailableException ignored) {
            // 결과 불명 상태를 유지한다. 수동 복구는 외부 명령을 재전송하지 않는다.
        }
        return recoveryTransactions.inspect(
                new InspectManualRecoveryQuery(claim.handoffId()));
    }

    public ManualRecoveryRefundPreview previewManualRecoveryRefund(
            PreviewManualRecoveryRefundQuery query
    ) {
        return recoveryTransactions.preview(query);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RefundResult requestManualRecoveryRefund(
            RequestManualRecoveryRefundCommand command
    ) {
        PaymentRecoveryTransactionService.ManualRefundExecutionClaim claim =
                recoveryTransactions.claimRefundExecution(command);
        if (claim.replayResult() != null) {
            return claim.replayResult();
        }
        try {
            RefundResult result = requestRefund(claim.command());
            recoveryTransactions.finishRefundExecution(
                    command.handoffId(), command.operationId(), result);
            return result;
        } catch (RuntimeException exception) {
            recoveryTransactions.markRefundExecutionUnknown(
                    command.handoffId(), command.operationId());
            throw exception;
        }
    }

    public ManualRecoveryRegistration registerManualRecoveryHandoff(
            RegisterManualRecoveryHandoffCommand command
    ) {
        return recoveryTransactions.register(command);
    }

    public List<ManualRecoveryHandoffClaim> claimManualRecoveryHandoffs(
            ClaimManualRecoveryHandoffsCommand command
    ) {
        return recoveryTransactions.claim(command);
    }

    public void acknowledgeManualRecoveryHandoff(
            AcknowledgeManualRecoveryHandoffCommand command
    ) {
        recoveryTransactions.acknowledge(command);
    }

    private static long parsePositiveId(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("consumerAccountId must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("consumerAccountId must be a positive number", exception);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean isRetryableReservationPreparationConflict(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof ConstraintViolationException violation
                    && violation.getSQLException() != null
                    && violation.getSQLException().getErrorCode() == 1062) {
                String constraintName = violation.getConstraintName();
                if (constraintName != null
                        && RESERVATION_PREPARATION_CONSTRAINTS.contains(constraintName)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean matchesProviderMapping(
            PaymentTransactionService.ConfirmationClaim claim,
            ProviderPayment providerPayment
    ) {
        return claim.portOnePaymentId().equals(providerPayment.portOnePaymentId())
                && claim.amountMinor() == providerPayment.amountMinor()
                && claim.currency().equals(providerPayment.currency());
    }
}
